// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.BASE;
import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.await;
import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import dev.vertique.inboxoutbox.query.OutboxEntryDetail;
import dev.vertique.inboxoutbox.query.OutboxEntryPayload;
import dev.vertique.inboxoutbox.query.OutboxEntrySummary;
import dev.vertique.inboxoutbox.query.OutboxPayloadTooLargeException;
import dev.vertique.inboxoutbox.query.OutboxQuery;
import dev.vertique.inboxoutbox.query.OutboxQueryConfig;
import dev.vertique.inboxoutbox.query.OutboxSort;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link PgOutboxQueryService} against a real PostgreSQL instance: the listing with every
 * filter, order and page boundary, the detail read in every state, and the size-guarded payload read.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgOutboxQueryServiceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("outbox_query_service")
            .withMigration("classpath:db/migration/inbox-outbox");

    private static final PageCursor ALL = PageCursor.first(OutboxQueryServiceLimits.MAX);

    static Vertx vertx;
    static OutboxFixture fixture;
    static PgOutboxQueryService service;
    static RecordingPool recording;
    static PgOutboxQueryService recordedService;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        fixture = new OutboxFixture(db, vertx);
        service = new PgOutboxQueryService(
                fixture.pool(),
                new PgDbExceptionMapper(),
                DbSchemas.empty(),
                OutboxQueryConfig.builder().build());
        recording = new RecordingPool(fixture.pool());
        recordedService = new PgOutboxQueryService(
                recording.pool(),
                new PgDbExceptionMapper(),
                DbSchemas.empty(),
                OutboxQueryConfig.builder().build());
    }

    @AfterAll
    static void tearDown() throws Exception {
        fixture.close();
        await(vertx.close());
    }

    @BeforeEach
    void truncate() throws SQLException {
        fixture.reset();
        recording.clear();
    }

    /** Page-size bounds as the service documents them, kept in one place for the tests. */
    private static final class OutboxQueryServiceLimits {
        static final int MAX = 200;

        private OutboxQueryServiceLimits() {}
    }

    private static List<Long> listIds(OutboxQuery query) throws Exception {
        return idsOf(await(service.list(query, ALL)));
    }

    private static List<Long> idsOf(PagedResult<OutboxEntrySummary> page) {
        return page.items().stream().map(OutboxEntrySummary::entryId).toList();
    }

    private static OutboxQuery.OutboxQueryBuilder deadLetters() {
        return OutboxQuery.builder().states(java.util.Set.of(OutboxEntryState.DEAD_LETTER));
    }

    // --- Listing: states ---

    @Nested
    @DisplayName("list: state filter")
    class StateFilter {

        @Test
        @DisplayName("a single state lists exactly the entries in that state, for each of the five states")
        void singleState() throws Exception {
            for (OutboxEntryState state : OutboxEntryState.values()) {
                fixture.seed().state(state.name()).insert();
            }

            for (OutboxEntryState state : OutboxEntryState.values()) {
                List<OutboxEntrySummary> items =
                        await(service.list(OutboxQuery.inStates(state), ALL)).items();

                assertEquals(1, items.size(), state.name());
                assertEquals(state, items.get(0).state());
            }
        }

        @Test
        @DisplayName("several states list the union, and nothing in a state that was not asked for")
        void severalStates() throws Exception {
            long pending = fixture.seed().state("PENDING").insert();
            fixture.seed().state("PROCESSING").insert();
            fixture.seed().state("PUBLISHED").insert();
            long dead = fixture.seed().state("DEAD_LETTER").insert();
            long discarded = fixture.seed().state("DISCARDED").insert();

            List<Long> ids = listIds(OutboxQuery.inStates(
                    OutboxEntryState.DISCARDED, OutboxEntryState.PENDING, OutboxEntryState.DEAD_LETTER));

            assertEquals(java.util.Set.of(pending, dead, discarded), java.util.Set.copyOf(ids));
            assertEquals(3, ids.size());
        }

        @Test
        @DisplayName("every state at once lists every entry")
        void everyState() throws Exception {
            for (OutboxEntryState state : OutboxEntryState.values()) {
                fixture.seed().state(state.name()).insert();
            }

            assertEquals(
                    5,
                    await(service.list(OutboxQuery.inStates(OutboxEntryState.values()), ALL))
                            .items()
                            .size());
        }

        @Test
        @DisplayName("a summary carries every listed column and none of the content")
        void summaryColumns() throws Exception {
            long id = fixture.seed().insert();

            List<OutboxEntrySummary> items =
                    await(service.list(deadLetters().build(), ALL)).items();

            assertEquals(
                    List.of(new OutboxEntrySummary(
                            id,
                            OutboxEntryState.DEAD_LETTER,
                            DestinationType.SERVICE,
                            "orders.target",
                            "order.created",
                            "Order",
                            "order-1",
                            5,
                            7,
                            "java.io.IOException",
                            BASE,
                            BASE.plusSeconds(100))),
                    items);
        }

        @Test
        @DisplayName("nullable columns read as null")
        void nullableColumns() throws Exception {
            fixture.seed().aggregate(null, null).errorType(null).insert();

            OutboxEntrySummary summary =
                    await(service.list(deadLetters().build(), ALL)).items().get(0);

            assertNull(summary.aggregateType());
            assertNull(summary.aggregateId());
            assertNull(summary.errorType());
        }

        @Test
        @DisplayName("an empty table lists an empty page with no neighbours")
        void emptyTable() throws Exception {
            PagedResult<OutboxEntrySummary> page =
                    await(service.list(deadLetters().build(), ALL));

            assertTrue(page.items().isEmpty());
            assertFalse(page.hasMore());
            assertFalse(page.hasPrevious());
        }
    }

    // --- Listing: other filters ---

    @Nested
    @DisplayName("list: filters")
    class Filters {

        @Test
        @DisplayName("destination type matches built-in and custom types")
        void destinationType() throws Exception {
            long service = fixture.seed().destinationType("SERVICE").insert();
            long kafka = fixture.seed().destinationType("KAFKA").insert();
            long custom = fixture.seed().destinationType("CUSTOM_X").insert();

            assertEquals(
                    List.of(service),
                    listIds(deadLetters()
                            .destinationType(DestinationType.SERVICE)
                            .build()));
            assertEquals(
                    List.of(kafka),
                    listIds(deadLetters().destinationType(DestinationType.KAFKA).build()));
            assertEquals(
                    List.of(custom),
                    listIds(deadLetters()
                            .destinationType(DestinationType.of("CUSTOM_X"))
                            .build()));
            assertEquals(
                    List.of(),
                    listIds(deadLetters()
                            .destinationType(DestinationType.DELAYED_JOB)
                            .build()));
        }

        @Test
        @DisplayName("destination matches the whole value, case-sensitively, and treats % and _ literally")
        void destination() throws Exception {
            long literal = fixture.seed().destination("a%b").insert();
            fixture.seed().destination("axb").insert();
            long underscore = fixture.seed().destination("a_b").insert();
            fixture.seed().destination("A%B").insert();
            fixture.seed().destination("a%b2").insert();
            fixture.seed().destination("xa%b").insert();

            assertEquals(
                    List.of(literal), listIds(deadLetters().destination("a%b").build()));
            assertEquals(
                    List.of(underscore),
                    listIds(deadLetters().destination("a_b").build()));
            assertEquals(List.of(), listIds(deadLetters().destination("a").build()));
        }

        @Test
        @DisplayName("event type matches the whole value")
        void eventType() throws Exception {
            long created = fixture.seed().eventType("order.created").insert();
            fixture.seed().eventType("order.created.v2").insert();
            fixture.seed().eventType("order.cancelled").insert();

            assertEquals(
                    List.of(created),
                    listIds(deadLetters().eventType("order.created").build()));
        }

        @Test
        @DisplayName("aggregate type matches the whole value and never matches an entry without an aggregate")
        void aggregateType() throws Exception {
            long order = fixture.seed().aggregate("Order", "o-1").insert();
            fixture.seed().aggregate("OrderLine", "o-1").insert();
            fixture.seed().aggregate(null, null).insert();

            assertEquals(
                    List.of(order), listIds(deadLetters().aggregateType("Order").build()));
        }

        @Test
        @DisplayName("aggregate id matches the whole value and never matches an entry without an aggregate")
        void aggregateId() throws Exception {
            long first = fixture.seed().aggregate("Order", "o-1").insert();
            fixture.seed().aggregate("Order", "o-10").insert();
            fixture.seed().aggregate(null, null).insert();

            assertEquals(
                    List.of(first), listIds(deadLetters().aggregateId("o-1").build()));
        }

        @Test
        @DisplayName("filters combine with AND, and with the state filter")
        void combined() throws Exception {
            long match = fixture.seed()
                    .destination("d1")
                    .eventType("e1")
                    .aggregate("T", "a1")
                    .insert();
            fixture.seed()
                    .destination("d1")
                    .eventType("e2")
                    .aggregate("T", "a1")
                    .insert();
            fixture.seed()
                    .destination("d2")
                    .eventType("e1")
                    .aggregate("T", "a1")
                    .insert();
            fixture.seed()
                    .destination("d1")
                    .eventType("e1")
                    .aggregate("T", "a2")
                    .insert();
            fixture.seed()
                    .state("PENDING")
                    .destination("d1")
                    .eventType("e1")
                    .aggregate("T", "a1")
                    .insert();

            assertEquals(
                    List.of(match),
                    listIds(deadLetters()
                            .destinationType(DestinationType.SERVICE)
                            .destination("d1")
                            .eventType("e1")
                            .aggregateType("T")
                            .aggregateId("a1")
                            .build()));
        }

        @Test
        @DisplayName("text filters that contain quotes are bound as values and match only themselves")
        void quotesAreValues() throws Exception {
            long quoted = fixture.seed().destination("it's \"x\"").insert();
            fixture.seed().destination("other").insert();

            assertEquals(
                    List.of(quoted),
                    listIds(deadLetters().destination("it's \"x\"").build()));
            assertEquals(
                    List.of(), listIds(deadLetters().destination("x' OR '1'='1").build()));
        }
    }

    // --- Listing: time ranges ---

    @Nested
    @DisplayName("list: time ranges")
    class TimeRanges {

        private long[] seedCreatedAtZeroTenTwenty() throws SQLException {
            return new long[] {
                fixture.seed().created(BASE).insert(),
                fixture.seed().created(BASE.plusSeconds(10)).insert(),
                fixture.seed().created(BASE.plusSeconds(20)).insert()
            };
        }

        private long[] seedUpdatedAtZeroTenTwenty() throws SQLException {
            return new long[] {
                fixture.seed().updated(BASE).insert(),
                fixture.seed().updated(BASE.plusSeconds(10)).insert(),
                fixture.seed().updated(BASE.plusSeconds(20)).insert()
            };
        }

        private List<Long> asc(OutboxQuery.OutboxQueryBuilder builder, OutboxSort sort) throws Exception {
            return listIds(builder.sort(sort).build());
        }

        @Test
        @DisplayName("created range: the start is inclusive and the end is exclusive")
        void createdRange() throws Exception {
            long[] ids = seedCreatedAtZeroTenTwenty();

            assertEquals(
                    List.of(ids[1], ids[2]),
                    asc(deadLetters().createdFrom(BASE.plusSeconds(10)), OutboxSort.CREATED_AT_ASC));
            assertEquals(
                    List.of(ids[0], ids[1]),
                    asc(deadLetters().createdBefore(BASE.plusSeconds(20)), OutboxSort.CREATED_AT_ASC));
            assertEquals(
                    List.of(ids[1]),
                    asc(
                            deadLetters().createdFrom(BASE.plusSeconds(10)).createdBefore(BASE.plusSeconds(20)),
                            OutboxSort.CREATED_AT_ASC));
            assertEquals(
                    List.of(ids[0]),
                    asc(
                            deadLetters().createdFrom(BASE).createdBefore(BASE.plusSeconds(10)),
                            OutboxSort.CREATED_AT_ASC));
            assertEquals(List.of(), asc(deadLetters().createdFrom(BASE.plusSeconds(21)), OutboxSort.CREATED_AT_ASC));
        }

        @Test
        @DisplayName("updated range: the start is inclusive and the end is exclusive")
        void updatedRange() throws Exception {
            long[] ids = seedUpdatedAtZeroTenTwenty();

            assertEquals(
                    List.of(ids[1], ids[2]),
                    asc(deadLetters().updatedFrom(BASE.plusSeconds(10)), OutboxSort.UPDATED_AT_ASC));
            assertEquals(
                    List.of(ids[0], ids[1]),
                    asc(deadLetters().updatedBefore(BASE.plusSeconds(20)), OutboxSort.UPDATED_AT_ASC));
            assertEquals(
                    List.of(ids[1]),
                    asc(
                            deadLetters().updatedFrom(BASE.plusSeconds(10)).updatedBefore(BASE.plusSeconds(20)),
                            OutboxSort.UPDATED_AT_ASC));
            assertEquals(List.of(), asc(deadLetters().updatedFrom(BASE.plusSeconds(21)), OutboxSort.UPDATED_AT_ASC));
        }

        @Test
        @DisplayName("the created range reads created_at and the updated range reads updated_at")
        void rangesReadTheirOwnColumn() throws Exception {
            long early =
                    fixture.seed().created(BASE).updated(BASE.plusSeconds(100)).insert();
            long late =
                    fixture.seed().created(BASE.plusSeconds(100)).updated(BASE).insert();

            assertEquals(
                    List.of(late),
                    listIds(deadLetters().createdFrom(BASE.plusSeconds(50)).build()));
            assertEquals(
                    List.of(early),
                    listIds(deadLetters().updatedFrom(BASE.plusSeconds(50)).build()));
            assertEquals(
                    List.of(early),
                    listIds(deadLetters().createdBefore(BASE.plusSeconds(50)).build()));
            assertEquals(
                    List.of(late),
                    listIds(deadLetters().updatedBefore(BASE.plusSeconds(50)).build()));
        }

        @Test
        @DisplayName("an instant with microsecond precision is compared exactly")
        void microsecondPrecision() throws Exception {
            Instant exact = BASE.plusNanos(123_456_000);
            long id = fixture.seed().updated(exact).insert();

            assertEquals(List.of(id), listIds(deadLetters().updatedFrom(exact).build()));
            assertEquals(List.of(), listIds(deadLetters().updatedBefore(exact).build()));
            assertEquals(
                    List.of(),
                    listIds(deadLetters().updatedFrom(exact.plusNanos(1_000)).build()));
        }
    }

    // --- Listing: order and paging ---

    @Nested
    @DisplayName("list: order and paging")
    class OrderAndPaging {

        @Test
        @DisplayName("all four orders sort by their timestamp and break ties by id in the same direction")
        void allOrders() throws Exception {
            // ids 1..4; created: 30, 10, 20, 10; updated: 5, 15, 15, 25
            long one = fixture.seed()
                    .created(BASE.plusSeconds(30))
                    .updated(BASE.plusSeconds(5))
                    .insert();
            long two = fixture.seed()
                    .created(BASE.plusSeconds(10))
                    .updated(BASE.plusSeconds(15))
                    .insert();
            long three = fixture.seed()
                    .created(BASE.plusSeconds(20))
                    .updated(BASE.plusSeconds(15))
                    .insert();
            long four = fixture.seed()
                    .created(BASE.plusSeconds(10))
                    .updated(BASE.plusSeconds(25))
                    .insert();

            assertEquals(
                    List.of(one, three, four, two),
                    listIds(deadLetters().sort(OutboxSort.CREATED_AT_DESC).build()));
            assertEquals(
                    List.of(two, four, three, one),
                    listIds(deadLetters().sort(OutboxSort.CREATED_AT_ASC).build()));
            assertEquals(
                    List.of(four, three, two, one),
                    listIds(deadLetters().sort(OutboxSort.UPDATED_AT_DESC).build()));
            assertEquals(
                    List.of(one, two, three, four),
                    listIds(deadLetters().sort(OutboxSort.UPDATED_AT_ASC).build()));
        }

        @Test
        @DisplayName("without a sort the listing is most recently updated first")
        void defaultOrder() throws Exception {
            long old = fixture.seed().updated(BASE.plusSeconds(1)).insert();
            long recent = fixture.seed().updated(BASE.plusSeconds(2)).insert();

            assertEquals(List.of(recent, old), listIds(deadLetters().build()));
        }

        @Test
        @DisplayName("forward paging over entries that share one timestamp visits each entry once, in every order")
        void forwardPagingOverTies() throws Exception {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                ids.add(fixture.seed().insert());
            }
            List<Long> ascending = ids;
            List<Long> descending = new ArrayList<>(ids).reversed();

            for (OutboxSort sort : OutboxSort.values()) {
                List<Long> expected = sort.ascending() ? ascending : descending;
                OutboxQuery query = deadLetters().sort(sort).build();

                PagedResult<OutboxEntrySummary> first = await(service.list(query, PageCursor.first(3)));
                assertEquals(expected.subList(0, 3), idsOf(first), sort.name());
                assertTrue(first.hasMore(), sort.name());
                assertFalse(first.hasPrevious(), sort.name());

                PagedResult<OutboxEntrySummary> second =
                        await(service.list(query, PageCursor.fromToken(first.nextCursorToken())));
                assertEquals(expected.subList(3, 6), idsOf(second), sort.name());
                assertTrue(second.hasMore(), sort.name());
                assertTrue(second.hasPrevious(), sort.name());

                PagedResult<OutboxEntrySummary> third =
                        await(service.list(query, PageCursor.fromToken(second.nextCursorToken())));
                assertEquals(expected.subList(6, 7), idsOf(third), sort.name());
                assertFalse(third.hasMore(), sort.name());
                assertTrue(third.hasPrevious(), sort.name());
            }
        }

        @Test
        @DisplayName("backward paging returns the preceding page in listing order, in every order")
        void backwardPagingOverTies() throws Exception {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                ids.add(fixture.seed().insert());
            }
            List<Long> descending = new ArrayList<>(ids).reversed();

            for (OutboxSort sort : OutboxSort.values()) {
                List<Long> expected = sort.ascending() ? ids : descending;
                OutboxQuery query = deadLetters().sort(sort).build();
                PagedResult<OutboxEntrySummary> first = await(service.list(query, PageCursor.first(3)));
                PagedResult<OutboxEntrySummary> second =
                        await(service.list(query, PageCursor.fromToken(first.nextCursorToken())));
                PagedResult<OutboxEntrySummary> third =
                        await(service.list(query, PageCursor.fromToken(second.nextCursorToken())));

                PagedResult<OutboxEntrySummary> backToSecond =
                        await(service.list(query, PageCursor.fromToken(third.previousCursorToken())));
                assertEquals(expected.subList(3, 6), idsOf(backToSecond), sort.name());
                assertTrue(backToSecond.hasMore(), sort.name());
                assertTrue(backToSecond.hasPrevious(), sort.name());

                PagedResult<OutboxEntrySummary> backToFirst =
                        await(service.list(query, PageCursor.fromToken(backToSecond.previousCursorToken())));
                assertEquals(expected.subList(0, 3), idsOf(backToFirst), sort.name());
                assertTrue(backToFirst.hasMore(), sort.name());
                assertFalse(backToFirst.hasPrevious(), sort.name());
            }
        }

        @Test
        @DisplayName("paging with distinct and tied timestamps mixed keeps one total order across page boundaries")
        void pagingOverMixedTimestamps() throws Exception {
            // updated: 1, 2, 2, 2, 3, 3, 4 (ids ascending)
            long[] seconds = {1, 2, 2, 2, 3, 3, 4};
            List<Long> ids = new ArrayList<>();
            for (long second : seconds) {
                ids.add(fixture.seed().updated(BASE.plusSeconds(second)).insert());
            }
            OutboxQuery query = deadLetters().sort(OutboxSort.UPDATED_AT_DESC).build();
            List<Long> visited = new ArrayList<>();

            PagedResult<OutboxEntrySummary> page = await(service.list(query, PageCursor.first(2)));
            visited.addAll(idsOf(page));
            while (page.hasMore()) {
                page = await(service.list(query, PageCursor.fromToken(page.nextCursorToken())));
                visited.addAll(idsOf(page));
            }

            assertEquals(ids.reversed(), visited);
        }

        @Test
        @DisplayName("a result that exactly fills the last page reports no further page")
        void exactFit() throws Exception {
            for (int i = 0; i < 6; i++) {
                fixture.seed().insert();
            }
            OutboxQuery query = deadLetters().build();

            PagedResult<OutboxEntrySummary> first = await(service.list(query, PageCursor.first(3)));
            PagedResult<OutboxEntrySummary> second =
                    await(service.list(query, PageCursor.fromToken(first.nextCursorToken())));

            assertEquals(3, second.size());
            assertFalse(second.hasMore());
        }

        @Test
        @DisplayName("page sizes 1 and 200 are accepted")
        void pageSizeBounds() throws Exception {
            fixture.seed().insert();
            fixture.seed().insert();

            PagedResult<OutboxEntrySummary> one =
                    await(service.list(deadLetters().build(), PageCursor.first(1)));
            PagedResult<OutboxEntrySummary> max =
                    await(service.list(deadLetters().build(), PageCursor.first(200)));

            assertEquals(1, one.size());
            assertTrue(one.hasMore());
            assertEquals(2, max.size());
        }

        @Test
        @DisplayName("a page size above 200 is rejected without touching the database")
        void pageSizeTooLarge() throws Exception {
            fixture.seed().insert();

            Throwable failure = failureOf(recordedService.list(deadLetters().build(), PageCursor.first(201)));

            assertEquals(
                    "page size must be between 1 and 200",
                    assertInstanceOf(ValidationException.class, failure).getMessage());
            assertTrue(recording.statements().isEmpty());
        }

        @Test
        @DisplayName("a backward cursor with a page size above 200 is rejected as well")
        void backwardPageSizeTooLarge() {
            PageCursor cursor = PageCursor.of(List.of(Instant.now(), 5L), 500, true);

            Throwable failure = failureOf(service.list(deadLetters().build(), cursor));

            assertInstanceOf(ValidationException.class, failure);
        }

        @Test
        @DisplayName("an absent query or cursor is rejected")
        void nullArguments() {
            Throwable noQuery = failureOf(service.list(null, ALL));
            Throwable noCursor = failureOf(service.list(deadLetters().build(), null));
            Throwable neither = failureOf(service.list(null, null));

            for (Throwable failure : List.of(noQuery, noCursor, neither)) {
                assertEquals(
                        "query and cursor must not be null",
                        assertInstanceOf(ValidationException.class, failure).getMessage());
            }
        }
    }

    // --- Projections ---

    @Nested
    @DisplayName("statements never select content")
    class Projections {

        private static final List<String> NEVER_SELECTED =
                List.of("payload", "headers", "metadata", "scope_key", "carrier_id");

        private void assertNoneReferenced(String sql) {
            for (String column : NEVER_SELECTED) {
                assertFalse(sql.contains(column), "must not reference " + column + ": " + sql);
            }
        }

        @Test
        @DisplayName("the list and detail statements reference neither payload, headers, metadata, scope nor carrier")
        void listAndDetail() throws Exception {
            long id = fixture.seed().insert();

            await(recordedService.list(deadLetters().aggregateId("order-1").build(), ALL));
            await(recordedService.getById(id));

            List<RecordingPool.Statement> statements = recording.statements();
            assertEquals(2, statements.size());
            for (RecordingPool.Statement statement : statements) {
                assertNoneReferenced(statement.sql());
                assertFalse(statement.results().isEmpty());
                for (var rows : statement.results()) {
                    for (String column : rows.columnsNames()) {
                        assertFalse(NEVER_SELECTED.contains(column), "returned column " + column);
                    }
                }
            }
        }

        @Test
        @DisplayName("filter values are bound parameters, never part of the statement text")
        void filtersAreBound() throws Exception {
            fixture.seed().insert();

            await(recordedService.list(
                    deadLetters()
                            .destination("distinct-destination-value")
                            .eventType("distinct-event-value")
                            .aggregateType("distinct-type-value")
                            .aggregateId("distinct-id-value")
                            .build(),
                    ALL));

            String sql = recording.statements().get(0).sql();
            assertFalse(sql.contains("distinct-"), sql);
        }

        @Test
        @DisplayName("the state filter is a literal predicate so a partial index can serve it")
        void stateIsLiteral() throws Exception {
            await(recordedService.list(OutboxQuery.inStates(OutboxEntryState.DISCARDED), ALL));
            await(recordedService.list(
                    OutboxQuery.inStates(OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING), ALL));

            List<RecordingPool.Statement> statements = recording.statements();
            assertTrue(
                    statements.get(0).sql().contains("state = 'DISCARDED'"),
                    statements.get(0).sql());
            assertTrue(
                    statements.get(1).sql().contains("state IN ('PENDING', 'DEAD_LETTER')"),
                    statements.get(1).sql());
        }
    }

    // --- Detail ---

    @Nested
    @DisplayName("getById")
    class Detail {

        @Test
        @DisplayName("reads the entry in every state with every scheduling, lease and failure column")
        void everyState() throws Exception {
            for (OutboxEntryState state : OutboxEntryState.values()) {
                Instant publishedAt = state == OutboxEntryState.PUBLISHED ? BASE.plusSeconds(50) : null;
                long id = fixture.seed()
                        .state(state.name())
                        .published(publishedAt)
                        .insert();

                Optional<OutboxEntryDetail> detail = await(service.getById(id));

                assertEquals(
                        Optional.of(new OutboxEntryDetail(
                                id,
                                state,
                                DestinationType.SERVICE,
                                "orders.target",
                                "order.created",
                                "Order",
                                "order-1",
                                5,
                                7,
                                "java.io.IOException",
                                BASE,
                                BASE.plusSeconds(100),
                                BASE.plusSeconds(7),
                                BASE.plusSeconds(5),
                                publishedAt,
                                BASE.plusSeconds(6),
                                "node-1",
                                "boom: gateway refused",
                                false)),
                        detail,
                        state.name());
            }
        }

        @Test
        @DisplayName("absent optional columns read as null")
        void nulls() throws Exception {
            long id = fixture.seed()
                    .state("PENDING")
                    .aggregate(null, null)
                    .scheduledNull()
                    .unclaimed()
                    .lastError(null)
                    .errorType(null)
                    .attempts(0, 20)
                    .insert();

            OutboxEntryDetail detail = await(service.getById(id)).orElseThrow();

            assertNull(detail.aggregateType());
            assertNull(detail.aggregateId());
            assertNull(detail.scheduledAt());
            assertNull(detail.publishedAt());
            assertNull(detail.claimedAt());
            assertNull(detail.claimedBy());
            assertNull(detail.lastError());
            assertNull(detail.errorType());
            assertFalse(detail.lastErrorTruncated());
            assertEquals(0, detail.attempt());
            assertEquals(20, detail.maxAttempts());
        }

        @Test
        @DisplayName("a last error of exactly 4096 characters is returned whole and not flagged")
        void lastErrorAtLimit() throws Exception {
            long id = fixture.seed().lastError("e".repeat(4096)).insert();

            OutboxEntryDetail detail = await(service.getById(id)).orElseThrow();

            assertEquals("e".repeat(4096), detail.lastError());
            assertFalse(detail.lastErrorTruncated());
        }

        @Test
        @DisplayName("a longer last error is cut at 4096 characters and flagged, however long it is")
        void lastErrorTruncated() throws Exception {
            long justOver = fixture.seed().lastError("e".repeat(4097)).insert();
            long huge = fixture.seed().lastError("x".repeat(100_000)).insert();

            OutboxEntryDetail over = await(service.getById(justOver)).orElseThrow();
            OutboxEntryDetail large = await(service.getById(huge)).orElseThrow();

            assertEquals("e".repeat(4096), over.lastError());
            assertTrue(over.lastErrorTruncated());
            assertEquals("x".repeat(4096), large.lastError());
            assertTrue(large.lastErrorTruncated());
        }

        @Test
        @DisplayName("the cut counts characters, not bytes")
        void lastErrorCutCountsCharacters() throws Exception {
            long id = fixture.seed().lastError("é€".repeat(3000)).insert();

            OutboxEntryDetail detail = await(service.getById(id)).orElseThrow();

            assertEquals(4096, detail.lastError().length());
            assertEquals("é€".repeat(2048), detail.lastError());
            assertTrue(detail.lastErrorTruncated());
        }

        @Test
        @DisplayName("a last error of supplementary characters is cut at 4096 characters, counted as code points")
        void lastErrorWithSupplementaryCharacters() throws Exception {
            long id = fixture.seed().lastError("\uD83D\uDE00".repeat(5000)).insert();
            long fits = fixture.seed().lastError("\uD83D\uDE00".repeat(3000)).insert();

            OutboxEntryDetail cut = await(service.getById(id)).orElseThrow();
            OutboxEntryDetail whole = await(service.getById(fits)).orElseThrow();

            assertEquals(4096, cut.lastError().codePointCount(0, cut.lastError().length()));
            assertTrue(cut.lastErrorTruncated());
            assertEquals(
                    3000, whole.lastError().codePointCount(0, whole.lastError().length()));
            assertFalse(whole.lastErrorTruncated());
        }

        @Test
        @DisplayName("an unknown id reads as empty")
        void unknown() throws Exception {
            assertEquals(Optional.empty(), await(service.getById(999_999)));
        }

        @Test
        @DisplayName("a non-positive id is rejected")
        void nonPositive() {
            assertEquals(
                    "entryId must be positive",
                    assertInstanceOf(ValidationException.class, failureOf(service.getById(0)))
                            .getMessage());
            assertEquals(
                    "entryId must be positive",
                    assertInstanceOf(ValidationException.class, failureOf(service.getById(-5)))
                            .getMessage());
        }
    }

    // --- Payload ---

    @Nested
    @DisplayName("getPayload")
    class Payload {

        private PgOutboxQueryService withLimit(int maxPayloadBytes, RecordingPool pool) {
            return new PgOutboxQueryService(
                    pool == null ? fixture.pool() : pool.pool(),
                    new PgDbExceptionMapper(),
                    DbSchemas.empty(),
                    OutboxQueryConfig.builder().maxPayloadBytes(maxPayloadBytes).build());
        }

        private long storedBytes(long id) throws SQLException {
            return fixture.longValue(
                    "SELECT octet_length(payload::text) + COALESCE(octet_length(headers::text), 0)"
                            + " FROM outbox WHERE id = ?",
                    id);
        }

        @Test
        @DisplayName("returns the stored payload JSON and the application headers, in every state")
        void everyState() throws Exception {
            for (OutboxEntryState state : OutboxEntryState.values()) {
                long id = fixture.seed().state(state.name()).insert();

                OutboxEntryPayload payload = await(service.getPayload(id)).orElseThrow();

                assertEquals(id, payload.entryId(), state.name());
                assertEquals(
                        fixture.string("SELECT payload::text FROM outbox WHERE id = ?", id), payload.payloadJson());
                assertEquals(
                        new JsonObject("{\"orderId\": 1, \"note\": \"café\"}"), new JsonObject(payload.payloadJson()));
                assertEquals(Map.of("traceparent", "abc", "tenant", "t-1"), payload.headers(), state.name());
            }
        }

        @Test
        @DisplayName("an entry without headers has an empty header map")
        void noHeaders() throws Exception {
            long id = fixture.seed().headers(null).insert();

            OutboxEntryPayload payload = await(service.getPayload(id)).orElseThrow();

            assertTrue(payload.headers().isEmpty());
            assertEquals(new JsonObject("{\"orderId\": 1, \"note\": \"café\"}"), new JsonObject(payload.payloadJson()));
        }

        @Test
        @DisplayName("header values that are not strings are rendered as text and a JSON null header is left out")
        void nonStringHeaders() throws Exception {
            long id = fixture.seed()
                    .headers("{\"a\": \"x\", \"n\": 5, \"b\": true, \"z\": null}")
                    .insert();

            OutboxEntryPayload payload = await(service.getPayload(id)).orElseThrow();

            assertEquals(Map.of("a", "x", "n", "5", "b", "true"), payload.headers());
        }

        @Test
        @DisplayName("a payload that is not a JSON object is returned as stored")
        void scalarPayload() throws Exception {
            long id = fixture.seed().payload("[1, 2, 3]").insert();

            assertEquals(
                    "[1, 2, 3]", await(service.getPayload(id)).orElseThrow().payloadJson());
        }

        @Test
        @DisplayName("content whose size equals the limit is returned, and one byte more is refused")
        void limitBoundary() throws Exception {
            long id = fixture.seed()
                    .payload("{\"v\": \"" + "x".repeat(200) + "\"}")
                    .insert();
            int size = (int) storedBytes(id);

            OutboxEntryPayload atLimit =
                    await(withLimit(size, null).getPayload(id)).orElseThrow();
            Throwable over = failureOf(withLimit(size - 1, null).getPayload(id));

            assertEquals(id, atLimit.entryId());
            assertInstanceOf(OutboxPayloadTooLargeException.class, over);
            assertEquals("The outbox entry content exceeds the configured limit", over.getMessage());
        }

        @Test
        @DisplayName("the limit counts the UTF-8 bytes of payload plus headers, not characters")
        void limitCountsUtf8BytesOfBoth() throws Exception {
            String payloadJson = "{\"v\": \"é€😀\"}";
            String headersJson = "{\"h\": \"é\"}";
            long id = fixture.seed().payload(payloadJson).headers(headersJson).insert();
            String canonicalPayload = fixture.string("SELECT payload::text FROM outbox WHERE id = ?", id);
            String canonicalHeaders = fixture.string("SELECT headers::text FROM outbox WHERE id = ?", id);
            int utf8Bytes = canonicalPayload.getBytes(StandardCharsets.UTF_8).length
                    + canonicalHeaders.getBytes(StandardCharsets.UTF_8).length;
            int chars = canonicalPayload.length() + canonicalHeaders.length();

            assertEquals(utf8Bytes, storedBytes(id));
            assertTrue(utf8Bytes > chars, "the fixture must hold multi-byte characters");
            assertEquals(
                    id,
                    await(withLimit(utf8Bytes, null).getPayload(id))
                            .orElseThrow()
                            .entryId());
            assertInstanceOf(
                    OutboxPayloadTooLargeException.class,
                    failureOf(withLimit(utf8Bytes - 1, null).getPayload(id)));
            // the headers alone push the content over a limit the payload alone would fit
            assertInstanceOf(
                    OutboxPayloadTooLargeException.class,
                    failureOf(withLimit(canonicalPayload.getBytes(StandardCharsets.UTF_8).length + 1, null)
                            .getPayload(id)));
        }

        @Test
        @DisplayName("an oversized entry's content never leaves the database")
        void oversizedValueIsNotTransferred() throws Exception {
            long id = fixture.seed()
                    .payload("{\"v\": \"" + "x".repeat(50_000) + "\"}")
                    .insert();
            RecordingPool pool = new RecordingPool(fixture.pool());

            Throwable failure = failureOf(withLimit(1024, pool).getPayload(id));

            assertInstanceOf(OutboxPayloadTooLargeException.class, failure);
            assertEquals(1, pool.statements().size());
            var rows = pool.statements().get(0).results();
            assertEquals(1, rows.size());
            var row = rows.get(0).iterator().next();
            assertTrue(row.getLong("content_bytes") > 50_000);
            assertNull(row.getString("payload_text"), "the payload text was not sent");
            assertNull(row.getString("headers_text"), "the headers text was not sent");
        }

        @Test
        @DisplayName("an entry within the limit has its content sent")
        void contentWithinLimitIsTransferred() throws Exception {
            long id = fixture.seed().insert();
            RecordingPool pool = new RecordingPool(fixture.pool());

            await(withLimit(1024, pool).getPayload(id));

            var row = pool.statements().get(0).results().get(0).iterator().next();
            assertEquals(storedBytes(id), row.getLong("content_bytes"));
            assertEquals(
                    fixture.string("SELECT payload::text FROM outbox WHERE id = ?", id), row.getString("payload_text"));
            assertEquals(
                    fixture.string("SELECT headers::text FROM outbox WHERE id = ?", id), row.getString("headers_text"));
        }

        @Test
        @DisplayName("the default limit of 1 MiB admits an entry just under it and refuses one over it")
        void defaultLimit() throws Exception {
            long fits = fixture.seed()
                    .payload("{\"v\": \"" + "x".repeat(1_000_000) + "\"}")
                    .headers(null)
                    .insert();
            long tooBig = fixture.seed()
                    .payload("{\"v\": \"" + "x".repeat(1_100_000) + "\"}")
                    .headers(null)
                    .insert();

            assertTrue(await(service.getPayload(fits)).isPresent());
            assertInstanceOf(OutboxPayloadTooLargeException.class, failureOf(service.getPayload(tooBig)));
        }

        @Test
        @DisplayName("an unknown id reads as empty, even when the limit is tiny")
        void unknown() throws Exception {
            assertEquals(Optional.empty(), await(service.getPayload(999_999)));
            assertEquals(Optional.empty(), await(withLimit(1, null).getPayload(999_999)));
        }

        @Test
        @DisplayName("a non-positive id is rejected")
        void nonPositive() {
            assertEquals(
                    "entryId must be positive",
                    assertInstanceOf(ValidationException.class, failureOf(service.getPayload(0)))
                            .getMessage());
            assertEquals(
                    "entryId must be positive",
                    assertInstanceOf(ValidationException.class, failureOf(service.getPayload(-1)))
                            .getMessage());
        }

        @Test
        @DisplayName("the payload statement references neither metadata, scope nor carrier")
        void projection() throws Exception {
            long id = fixture.seed().insert();

            await(recordedService.getPayload(id));

            String sql = recording.statements().get(0).sql();
            for (String column : List.of("metadata", "scope_key", "carrier_id", "claimed_by", "last_error")) {
                assertFalse(sql.contains(column), "must not reference " + column + ": " + sql);
            }
        }
    }

    // --- Failures ---

    @Nested
    @DisplayName("database failures")
    class Failures {

        private PgOutboxQueryService brokenService() {
            return new PgOutboxQueryService(
                    fixture.pool(),
                    new PgDbExceptionMapper(),
                    new DbSchemas(Map.of("inbox-outbox", "no_such_schema")),
                    OutboxQueryConfig.builder().build());
        }

        private void assertOpaque(Throwable failure, String operation) {
            InboxOutboxPersistenceException persistence =
                    assertInstanceOf(InboxOutboxPersistenceException.class, failure);
            assertFalse(persistence.retryable());
            String message = persistence.getMessage();
            assertTrue(message.contains("'" + operation + "'"), message);
            for (String leaked :
                    List.of("SELECT", "FROM", "no_such_schema", "outbox\"", "does not exist", "WHERE", "$1")) {
                assertFalse(message.contains(leaked), "message leaks '" + leaked + "': " + message);
            }
        }

        @Test
        @DisplayName("list, getById and getPayload report a missing table as a persistence exception without SQL text")
        void persistenceFailure() {
            PgOutboxQueryService broken = brokenService();

            assertOpaque(failureOf(broken.list(deadLetters().build(), ALL)), "outbox entry list");
            assertOpaque(failureOf(broken.getById(1)), "outbox entry get");
            assertOpaque(failureOf(broken.getPayload(1)), "outbox entry payload");
        }
    }
}
