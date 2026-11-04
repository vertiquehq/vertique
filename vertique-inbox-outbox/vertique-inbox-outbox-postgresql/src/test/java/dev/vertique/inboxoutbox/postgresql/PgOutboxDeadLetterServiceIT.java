// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static dev.vertique.inboxoutbox.postgresql.DeadLetterFixture.BASE;
import static dev.vertique.inboxoutbox.postgresql.DeadLetterFixture.await;
import static dev.vertique.inboxoutbox.postgresql.DeadLetterFixture.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig;
import dev.vertique.inboxoutbox.admin.OutboxAdminAction;
import dev.vertique.inboxoutbox.admin.OutboxAdminActor;
import dev.vertique.inboxoutbox.admin.OutboxAdminCommand;
import dev.vertique.inboxoutbox.admin.OutboxAdminDisposition;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryEntry;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryKey;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryPage;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryQuery;
import dev.vertique.inboxoutbox.admin.OutboxAdminReceipt;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterConflictException;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterConflictException.Reason;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterContent;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterDetail;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterKey;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterNotFoundException;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterPage;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterQuery;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterSummary;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgNotification;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
 * Integration tests of {@link PgOutboxDeadLetterService} against a real PostgreSQL: reads, commands, every
 * conflict path, replay, rollback, concurrency barriers, history and receipt retention. Locks are held by plain
 * JDBC connections so that each interleaving is forced by an explicit transaction barrier, never by timing.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgOutboxDeadLetterServiceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("outbox_dead_letter_admin")
            .withMigration("classpath:db/migration/inbox-outbox");

    private static final OutboxAdminActor ACTOR = new OutboxAdminActor("user", "alice");
    private static final String REASON = "  needs a retry – ünïcode\n second line  ";

    static Vertx vertx;
    static DeadLetterFixture fixture;
    static PgOutboxDeadLetterService service;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        fixture = new DeadLetterFixture(db, vertx);
        service = serviceWith(InboxOutboxAdminConfig.builder().build());
    }

    @AfterAll
    static void tearDown() throws Exception {
        fixture.close();
        await(vertx.close());
    }

    @BeforeEach
    void truncate() throws SQLException {
        fixture.reset();
    }

    private static PgOutboxDeadLetterService serviceWith(InboxOutboxAdminConfig config) {
        return new PgOutboxDeadLetterService(fixture.pool(), new PgDbExceptionMapper(), DbSchemas.empty(), config);
    }

    private static OutboxAdminCommand command(long entryId, long revision) {
        return new OutboxAdminCommand(UUID.randomUUID(), entryId, revision, REASON, ACTOR);
    }

    private static List<Long> ids(OutboxDeadLetterPage page) {
        return page.items().stream().map(OutboxDeadLetterSummary::entryId).toList();
    }

    private static OutboxDeadLetterQuery.Builder query() {
        return OutboxDeadLetterQuery.builder();
    }

    private static void assertConflict(Reason reason, Throwable failure) {
        OutboxDeadLetterConflictException conflict = assertInstanceOf(OutboxDeadLetterConflictException.class, failure);
        assertEquals(reason, conflict.reason());
    }

    // --- list ---

    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("lists only dead letters, newest failure first, and applies every filter with AND")
        void filters() throws Exception {
            long a = fixture.seed()
                    .destination("d1")
                    .eventType("e1")
                    .aggregate("Order", "o-1")
                    .errorType("E1")
                    .created(BASE)
                    .updated(BASE.plusSeconds(10))
                    .insert();
            long b = fixture.seed()
                    .destinationType("KAFKA")
                    .destination("d2")
                    .eventType("e2")
                    .aggregate("Invoice", "i-1")
                    .errorType("E2")
                    .created(BASE.plusSeconds(100))
                    .updated(BASE.plusSeconds(200))
                    .insert();
            fixture.seed().state("PENDING").destination("d1").insert();
            fixture.seed().state("PUBLISHED").destination("d1").insert();
            fixture.seed().state("PROCESSING").destination("d1").insert();

            assertEquals(List.of(b, a), ids(await(service.list(query().build()))));
            assertEquals(List.of(a), ids(await(service.list(query().entryId(a).build()))));
            assertEquals(List.of(), ids(await(service.list(query().entryId(999L).build()))));
            assertEquals(
                    List.of(b),
                    ids(await(service.list(
                            query().destinationType(DestinationType.KAFKA).build()))));
            assertEquals(
                    List.of(a), ids(await(service.list(query().destination("d1").build()))));
            assertEquals(
                    List.of(b), ids(await(service.list(query().eventType("e2").build()))));
            assertEquals(
                    List.of(a),
                    ids(await(service.list(query().aggregateType("Order").build()))));
            assertEquals(
                    List.of(b),
                    ids(await(service.list(query().aggregateId("i-1").build()))));
            assertEquals(
                    List.of(b), ids(await(service.list(query().errorType("E2").build()))));
            // exact match, not prefix or case-insensitive
            assertEquals(
                    List.of(), ids(await(service.list(query().destination("d").build()))));
            assertEquals(
                    List.of(), ids(await(service.list(query().errorType("e2").build()))));
            // failed range: inclusive from, exclusive before
            assertEquals(
                    List.of(b, a),
                    ids(await(service.list(
                            query().failedFrom(BASE.plusSeconds(10)).build()))));
            assertEquals(
                    List.of(b),
                    ids(await(service.list(
                            query().failedFrom(BASE.plusSeconds(11)).build()))));
            assertEquals(
                    List.of(a),
                    ids(await(service.list(
                            query().failedBefore(BASE.plusSeconds(200)).build()))));
            assertEquals(
                    List.of(b, a),
                    ids(await(service.list(
                            query().failedBefore(BASE.plusSeconds(201)).build()))));
            // created range: inclusive from, exclusive before
            assertEquals(
                    List.of(b),
                    ids(await(service.list(
                            query().createdFrom(BASE.plusSeconds(100)).build()))));
            assertEquals(
                    List.of(a),
                    ids(await(service.list(
                            query().createdBefore(BASE.plusSeconds(100)).build()))));
            // combined filters narrow, never widen
            assertEquals(
                    List.of(a),
                    ids(await(service.list(query().destination("d1")
                            .eventType("e1")
                            .aggregateId("o-1")
                            .failedFrom(BASE)
                            .createdBefore(BASE.plusSeconds(1))
                            .build()))));
            assertEquals(
                    List.of(),
                    ids(await(service.list(
                            query().destination("d1").eventType("e2").build()))));
        }

        @Test
        @DisplayName("the summary carries the row's identity, revision and failure facts and nothing else")
        void summaryFields() throws Exception {
            long id = fixture.seed().revision(4).attempts(3, 9).insert();

            OutboxDeadLetterSummary summary =
                    await(service.list(query().build())).items().get(0);

            assertEquals(id, summary.entryId());
            assertEquals(4, summary.revision());
            assertEquals(DestinationType.SERVICE, summary.destinationType());
            assertEquals("orders.target", summary.destination());
            assertEquals("order.created", summary.eventType());
            assertEquals("Order", summary.aggregateType());
            assertEquals("order-1", summary.aggregateId());
            assertEquals(3, summary.attempt());
            assertEquals(9, summary.maxAttempts());
            assertEquals("java.io.IOException", summary.errorType());
            assertEquals(BASE, summary.createdAt());
            assertEquals(BASE.plusSeconds(100), summary.failedAt());
        }

        @Test
        @DisplayName("keyset paging over rows that share updated_at is total, stable and has no overlap")
        void tiedKeysetOrdering() throws Exception {
            Instant tie = BASE.plusSeconds(500);
            List<Long> tied = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tied.add(fixture.seed().updated(tie).insert());
            }
            long older = fixture.seed().updated(BASE.plusSeconds(1)).insert();
            long newer = fixture.seed().updated(BASE.plusSeconds(900)).insert();
            List<Long> expected = new ArrayList<>();
            expected.add(newer);
            for (int i = tied.size() - 1; i >= 0; i--) {
                expected.add(tied.get(i));
            }
            expected.add(older);

            for (int pageSize : new int[] {1, 2, 3, 7, 50}) {
                List<Long> walked = new ArrayList<>();
                OutboxDeadLetterKey after = null;
                int pages = 0;
                do {
                    OutboxDeadLetterPage page = await(
                            service.list(query().limit(pageSize).after(after).build()));
                    assertTrue(page.items().size() <= pageSize);
                    walked.addAll(ids(page));
                    if (page.next().isPresent()) {
                        OutboxDeadLetterSummary last =
                                page.items().get(page.items().size() - 1);
                        assertEquals(
                                new OutboxDeadLetterKey(last.failedAt(), last.entryId()),
                                page.next().get());
                        assertEquals(pageSize, page.items().size(), "a next key means a full page");
                    }
                    after = page.next().orElse(null);
                    pages++;
                } while (after != null && pages < 20);
                assertEquals(expected, walked, "page size " + pageSize);
            }
        }

        @Test
        @DisplayName("a page that exactly exhausts the rows has no next key")
        void exactFitHasNoNext() throws Exception {
            fixture.seed().insert();
            fixture.seed().insert();

            OutboxDeadLetterPage page = await(service.list(query().limit(2).build()));

            assertEquals(2, page.items().size());
            assertTrue(page.next().isEmpty());
        }
    }

    // --- find / readContent ---

    @Nested
    @DisplayName("find and readContent")
    class DetailTests {

        @Test
        @DisplayName("find returns the detail, and empty for an absent or non-dead-letter row")
        void findDetail() throws Exception {
            long id = fixture.seed().revision(2).insert();
            long pending = fixture.seed().state("PENDING").insert();

            OutboxDeadLetterDetail detail = await(service.find(id)).orElseThrow();

            assertEquals(id, detail.entryId());
            assertEquals(2, detail.revision());
            assertEquals(BASE.plusSeconds(7), detail.availableAt());
            assertEquals(Optional.of(BASE.plusSeconds(5)), detail.scheduledAt());
            assertEquals("boom: gateway refused", detail.lastError());
            assertFalse(detail.lastErrorTruncated());
            assertTrue(await(service.find(pending)).isEmpty());
            assertTrue(await(service.find(424242)).isEmpty());
        }

        @Test
        @DisplayName("the last error is cut at the database boundary at 4096 characters and flagged")
        void lastErrorTruncation() throws Exception {
            long exact = fixture.seed().lastError("é".repeat(4096)).insert();
            long over = fixture.seed().lastError("é".repeat(4097)).insert();
            // supplementary characters count once, as the detail record counts code points
            long emoji = fixture.seed().lastError("😀".repeat(4100)).insert();
            long none = fixture.seed().lastError(null).scheduledNull().insert();

            OutboxDeadLetterDetail exactDetail = await(service.find(exact)).orElseThrow();
            OutboxDeadLetterDetail overDetail = await(service.find(over)).orElseThrow();
            OutboxDeadLetterDetail emojiDetail = await(service.find(emoji)).orElseThrow();
            OutboxDeadLetterDetail noneDetail = await(service.find(none)).orElseThrow();

            assertEquals(4096, exactDetail.lastError().length());
            assertFalse(exactDetail.lastErrorTruncated());
            assertEquals("é".repeat(4096), overDetail.lastError());
            assertTrue(overDetail.lastErrorTruncated());
            assertEquals(
                    4096,
                    emojiDetail
                            .lastError()
                            .codePointCount(0, emojiDetail.lastError().length()));
            assertTrue(emojiDetail.lastErrorTruncated());
            assertNull(noneDetail.lastError());
            assertFalse(noneDetail.lastErrorTruncated());
            assertEquals(Optional.empty(), noneDetail.scheduledAt());
        }

        @Test
        @DisplayName("content is the raw stored JSON text plus application headers only")
        void content() throws Exception {
            long id = fixture.seed().revision(3).insert();

            OutboxDeadLetterContent content = await(service.readContent(id)).orElseThrow();

            assertEquals(id, content.entryId());
            assertEquals(3, content.revision());
            assertEquals(fixture.string("SELECT payload::text FROM outbox WHERE id = ?", id), content.payloadJson());
            assertEquals(Map.of("traceparent", "abc", "tenant", "t-1"), content.headers());
        }

        @Test
        @DisplayName("an entry without headers reads an empty header map; JSON null headers are left out")
        void contentWithoutHeaders() throws Exception {
            long none = fixture.seed().headers(null).insert();
            long withNull = fixture.seed()
                    .headers("{\"a\": \"1\", \"b\": null, \"n\": 5}")
                    .insert();

            assertEquals(
                    Map.of(), await(service.readContent(none)).orElseThrow().headers());
            assertEquals(
                    Map.of("a", "1", "n", "5"),
                    await(service.readContent(withNull)).orElseThrow().headers());
        }

        @Test
        @DisplayName("content is empty for an absent or non-dead-letter row")
        void contentAbsent() throws Exception {
            long pending = fixture.seed().state("PENDING").insert();

            assertTrue(await(service.readContent(pending)).isEmpty());
            assertTrue(await(service.readContent(777)).isEmpty());
        }

        @Test
        @DisplayName("the limit counts the UTF-8 bytes of payload plus headers text, inclusive at the boundary")
        void contentLimitBoundary() throws Exception {
            long id = fixture.seed().insert();
            long size = fixture.longValue(
                    "SELECT octet_length(payload::text)"
                            + " + COALESCE(octet_length(headers::text), 0) FROM outbox WHERE id = ?",
                    id);
            // the multi-byte payload makes bytes differ from characters
            long characters = fixture.longValue(
                    "SELECT char_length(payload::text) + COALESCE(char_length(headers::text), 0) FROM outbox"
                            + " WHERE id = ?",
                    id);
            assertTrue(size > characters);

            PgOutboxDeadLetterService exact = serviceWith(
                    InboxOutboxAdminConfig.builder().maxContentBytes((int) size).build());
            PgOutboxDeadLetterService oneBelow = serviceWith(InboxOutboxAdminConfig.builder()
                    .maxContentBytes((int) size - 1)
                    .build());
            PgOutboxDeadLetterService characterCount = serviceWith(InboxOutboxAdminConfig.builder()
                    .maxContentBytes((int) characters)
                    .build());

            assertTrue(await(exact.readContent(id)).isPresent());
            assertConflict(Reason.CONTENT_LIMIT_EXCEEDED, failureOf(oneBelow.readContent(id)));
            assertConflict(Reason.CONTENT_LIMIT_EXCEEDED, failureOf(characterCount.readContent(id)));
        }

        @Test
        @DisplayName("the size limit does not turn a non-dead-letter row into a conflict")
        void limitDoesNotLeakNonDeadLetter() throws Exception {
            long pending = fixture.seed().state("PENDING").insert();
            PgOutboxDeadLetterService tiny = serviceWith(
                    InboxOutboxAdminConfig.builder().maxContentBytes(1).build());

            assertTrue(await(tiny.readContent(pending)).isEmpty());
        }
    }

    // --- retry / discard ---

    @Nested
    @DisplayName("retry and discard")
    class CommandTests {

        @Test
        @DisplayName("retry resets only the documented fields and preserves every other column")
        void retryPreservesEverythingElse() throws Exception {
            long id = fixture.seed().revision(3).insert();
            Map<String, Object> before = fixture.outboxRow(id);
            Instant started = Instant.now().minusSeconds(5);

            OutboxAdminReceipt receipt = await(service.retry(command(id, 3)));

            assertEquals(OutboxAdminAction.RETRY, receipt.action());
            assertEquals(OutboxAdminDisposition.REQUEUED, receipt.disposition());
            assertEquals(id, receipt.entryId());
            assertEquals(3, receipt.previousRevision());
            assertEquals(OptionalLong.of(4), receipt.resultRevision());
            assertTrue(receipt.recordedAt().isAfter(started));
            Map<String, Object> after = fixture.outboxRow(id);
            Set<String> reset = Set.of(
                    "state",
                    "attempt",
                    "admin_revision",
                    "claimed_at",
                    "claimed_by",
                    "last_error",
                    "error_type",
                    "available_at",
                    "updated_at");
            for (Map.Entry<String, Object> column : before.entrySet()) {
                if (!reset.contains(column.getKey())) {
                    assertEquals(column.getValue(), after.get(column.getKey()), "column " + column.getKey());
                }
            }
            assertEquals("PENDING", after.get("state"));
            assertEquals(0, after.get("attempt"));
            assertEquals(4L, after.get("admin_revision"));
            assertNull(after.get("claimed_at"));
            assertNull(after.get("claimed_by"));
            assertNull(after.get("last_error"));
            assertNull(after.get("error_type"));
            assertTrue(
                    ((java.sql.Timestamp) after.get("available_at")).toInstant().isAfter(started));
            assertTrue(
                    ((java.sql.Timestamp) after.get("updated_at")).toInstant().isAfter(started));
            // the row is claimable again and no longer listed
            assertTrue(await(service.list(query().build())).items().isEmpty());
        }

        @Test
        @DisplayName("retry stores the receipt with the exact reason, actor, prior failure and target identity")
        void retryReceiptRow() throws Exception {
            long id = fixture.seed().revision(3).attempts(5, 7).insert();
            OutboxAdminCommand command = command(id, 3);

            await(service.retry(command));

            try (Connection connection = fixture.connect();
                    var statement = connection.prepareStatement("SELECT * FROM outbox_admin_operations");
                    var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(command.operationId(), rows.getObject("operation_id", UUID.class));
                assertEquals("RETRY", rows.getString("action"));
                assertEquals(id, rows.getLong("entry_id"));
                assertEquals(3, rows.getLong("expected_revision"));
                assertEquals(REASON, rows.getString("reason"));
                assertEquals("user", rows.getString("actor_type"));
                assertEquals("alice", rows.getString("actor_id"));
                assertEquals(5, rows.getInt("attempt"));
                assertEquals(7, rows.getInt("max_attempts"));
                assertEquals("java.io.IOException", rows.getString("error_type"));
                assertEquals("SERVICE", rows.getString("destination_type"));
                assertEquals("orders.target", rows.getString("destination"));
                assertEquals("order.created", rows.getString("event_type"));
                assertEquals("Order", rows.getString("aggregate_type"));
                assertEquals("order-1", rows.getString("aggregate_id"));
                assertEquals("REQUEUED", rows.getString("disposition"));
                assertEquals(4, rows.getLong("result_revision"));
                assertNotNull(rows.getTimestamp("recorded_at"));
                assertFalse(rows.next());
            }
        }

        @Test
        @DisplayName("the receipt table holds no payload, headers, raw error text or metadata column")
        void receiptTableIsNarrow() throws Exception {
            List<String> columns = new ArrayList<>();
            try (Connection connection = fixture.connect();
                    var statement = connection.prepareStatement("SELECT column_name FROM information_schema.columns"
                            + " WHERE table_name = 'outbox_admin_operations' AND table_schema = current_schema()");
                    var rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }

            assertEquals(
                    Set.of(
                            "operation_id",
                            "action",
                            "entry_id",
                            "expected_revision",
                            "reason",
                            "actor_type",
                            "actor_id",
                            "recorded_at",
                            "attempt",
                            "max_attempts",
                            "error_type",
                            "destination_type",
                            "destination",
                            "event_type",
                            "aggregate_type",
                            "aggregate_id",
                            "disposition",
                            "result_revision"),
                    new HashSet<>(columns));
        }

        @Test
        @DisplayName("retry emits the outbox notification, and a rejected retry emits none")
        void retryNotifies() throws Exception {
            long id = fixture.seed().insert();
            BlockingQueue<PgNotification> notifications = new LinkedBlockingQueue<>();
            PgConnection listener = await(PgConnection.connect(vertx, fixture.connectOptions()));
            try {
                listener.notificationHandler(notifications::add);
                await(listener.query("LISTEN transactional_outbox_channel").execute());

                assertConflict(Reason.REVISION_CONFLICT, failureOf(service.retry(command(id, 9))));
                assertNull(notifications.poll(300, TimeUnit.MILLISECONDS), "a rejected retry must not notify");

                await(service.retry(command(id, 0)));

                PgNotification notification = notifications.poll(5, TimeUnit.SECONDS);
                assertNotNull(notification, "the committed retry must notify");
                assertEquals("transactional_outbox_channel", notification.getChannel());
                assertEquals(String.valueOf(id), notification.getPayload());
            } finally {
                await(listener.close());
            }
        }

        @Test
        @DisplayName("discard deletes only the targeted row and records a receipt without a result revision")
        void discardDeletesOnlyTarget() throws Exception {
            long target = fixture.seed().revision(1).insert();
            long other = fixture.seed().insert();
            long pending = fixture.seed().state("PENDING").insert();
            OutboxAdminCommand command = command(target, 1);

            OutboxAdminReceipt receipt = await(service.discard(command));

            assertEquals(OutboxAdminAction.DISCARD, receipt.action());
            assertEquals(OutboxAdminDisposition.DISCARDED, receipt.disposition());
            assertEquals(1, receipt.previousRevision());
            assertEquals(OptionalLong.empty(), receipt.resultRevision());
            assertFalse(fixture.outboxExists(target));
            assertTrue(fixture.outboxExists(other));
            assertTrue(fixture.outboxExists(pending));
            assertEquals(1, fixture.receipts());
            assertNull(fixture.string(
                    "SELECT result_revision::text FROM outbox_admin_operations WHERE operation_id = ?",
                    command.operationId()));
            assertEquals(
                    "DISCARDED",
                    fixture.string(
                            "SELECT disposition FROM outbox_admin_operations WHERE operation_id = ?",
                            command.operationId()));
        }

        @Test
        @DisplayName("each conflict path reports its reason and leaves neither a receipt nor a change")
        void conflictPaths() throws Exception {
            long dead = fixture.seed().revision(2).insert();
            long pending = fixture.seed().state("PENDING").insert();
            long exhausted = fixture.seed().revision(Long.MAX_VALUE).insert();
            Map<Long, Map<String, Object>> before = new java.util.HashMap<>();
            for (long id : List.of(dead, pending, exhausted)) {
                before.put(id, fixture.outboxRow(id));
            }

            Throwable missingRetry = failureOf(service.retry(command(555, 0)));
            Throwable missingDiscard = failureOf(service.discard(command(555, 0)));
            assertEquals(
                    555,
                    assertInstanceOf(OutboxDeadLetterNotFoundException.class, missingRetry)
                            .entryId());
            assertInstanceOf(OutboxDeadLetterNotFoundException.class, missingDiscard);

            assertConflict(Reason.NOT_DEAD_LETTER, failureOf(service.retry(command(pending, 0))));
            assertConflict(Reason.NOT_DEAD_LETTER, failureOf(service.discard(command(pending, 0))));
            assertConflict(Reason.REVISION_CONFLICT, failureOf(service.retry(command(dead, 1))));
            assertConflict(Reason.REVISION_CONFLICT, failureOf(service.retry(command(dead, 3))));
            assertConflict(Reason.REVISION_CONFLICT, failureOf(service.discard(command(dead, 1))));
            assertConflict(Reason.REVISION_EXHAUSTED, failureOf(service.retry(command(exhausted, Long.MAX_VALUE))));
            assertConflict(Reason.REVISION_CONFLICT, failureOf(service.retry(command(exhausted, 0))));

            assertEquals(0, fixture.receipts(), "a rejected command leaves no receipt");
            for (Map.Entry<Long, Map<String, Object>> entry : before.entrySet()) {
                assertEquals(entry.getValue(), fixture.outboxRow(entry.getKey()), "row " + entry.getKey());
            }
        }

        @Test
        @DisplayName("an exhausted revision can still be discarded")
        void exhaustedRevisionCanBeDiscarded() throws Exception {
            long id = fixture.seed().revision(Long.MAX_VALUE).insert();

            OutboxAdminReceipt receipt = await(service.discard(command(id, Long.MAX_VALUE)));

            assertEquals(Long.MAX_VALUE, receipt.previousRevision());
            assertFalse(fixture.outboxExists(id));
        }

        @Test
        @DisplayName("exception messages carry no reason, actor or SQL")
        void messagesAreSafe() throws Exception {
            long dead = fixture.seed().insert();
            OutboxAdminCommand secret = new OutboxAdminCommand(
                    UUID.randomUUID(), dead, 5, "secret-reason-text", new OutboxAdminActor("secret-type", "secret-id"));

            List<Throwable> failures = List.of(
                    failureOf(service.retry(secret)),
                    failureOf(service.discard(new OutboxAdminCommand(
                            UUID.randomUUID(), 999, 0, "secret-reason-text", new OutboxAdminActor("t", "secret-id")))));

            for (Throwable failure : failures) {
                String message = String.valueOf(failure.getMessage());
                assertFalse(message.contains("secret"), message);
                assertFalse(message.toUpperCase().contains("SELECT"), message);
            }
        }

        @Test
        @DisplayName("a database failure becomes InboxOutboxPersistenceException with a sanitized message")
        void databaseFailureIsTranslated() throws Exception {
            PgOutboxDeadLetterService broken = new PgOutboxDeadLetterService(
                    fixture.pool(),
                    new PgDbExceptionMapper(),
                    new DbSchemas(Map.of("inbox-outbox", "no_such_schema")),
                    InboxOutboxAdminConfig.builder().build());
            OutboxAdminCommand secret = new OutboxAdminCommand(
                    UUID.randomUUID(), 1, 0, "secret-reason-text", new OutboxAdminActor("secret-type", "secret-id"));

            for (Future<?> failing : List.of(
                    broken.list(query().build()),
                    broken.find(1),
                    broken.readContent(1),
                    broken.history(OutboxAdminHistoryQuery.of(1)),
                    broken.retry(secret),
                    broken.discard(secret),
                    broken.cleanupOperations(1, 1))) {
                Throwable failure = failureOf(failing);

                InboxOutboxPersistenceException persistence =
                        assertInstanceOf(InboxOutboxPersistenceException.class, failure);
                String message = persistence.getMessage();
                assertFalse(message.contains("secret"), message);
                assertFalse(message.contains("no_such_schema"), message);
                assertFalse(message.toUpperCase().contains("SELECT "), message);
                assertNotNull(persistence.getCause());
            }
        }
    }

    // --- replay and operation id reuse ---

    @Nested
    @DisplayName("idempotent replay")
    class ReplayTests {

        @Test
        @DisplayName("a replay returns the original receipt without touching the row, even after it failed again")
        void replayAfterSecondDeadLetterEpisode() throws Exception {
            long id = fixture.seed().revision(3).insert();
            OutboxAdminCommand command = command(id, 3);
            OutboxAdminReceipt first = await(service.retry(command));
            Map<String, Object> afterFirst = fixture.outboxRow(id);

            // an immediate replay
            assertEquals(first, await(service.retry(command)));
            assertEquals(afterFirst, fixture.outboxRow(id));

            // the entry fails again: a second dead-letter episode with a newer revision
            fixture.exec(
                    "UPDATE outbox SET state = 'DEAD_LETTER', attempt = 7, admin_revision = 9,"
                            + " last_error = 'again', updated_at = ? WHERE id = ?",
                    BASE.plusSeconds(1000),
                    id);
            Map<String, Object> secondEpisode = fixture.outboxRow(id);

            assertEquals(first, await(service.retry(command)));
            assertEquals(secondEpisode, fixture.outboxRow(id), "a replay must not requeue the new episode");
            assertEquals(1, fixture.receipts());
        }

        @Test
        @DisplayName("a replay still returns the receipt after the entry was discarded")
        void replayAfterDiscard() throws Exception {
            long id = fixture.seed().insert();
            OutboxAdminCommand retry = command(id, 0);
            OutboxAdminReceipt retried = await(service.retry(retry));
            fixture.exec("UPDATE outbox SET state = 'DEAD_LETTER' WHERE id = ?", id);
            OutboxAdminCommand discard = command(id, 1);
            OutboxAdminReceipt discarded = await(service.discard(discard));
            assertFalse(fixture.outboxExists(id));

            assertEquals(retried, await(service.retry(retry)));
            assertEquals(discarded, await(service.discard(discard)));
            assertFalse(fixture.outboxExists(id));
            assertEquals(2, fixture.receipts());
        }

        @Test
        @DisplayName("the same operation id with a different action, entry, revision, reason or actor conflicts")
        void operationIdReuseWithDifferentIntent() throws Exception {
            long id = fixture.seed().insert();
            long other = fixture.seed().insert();
            UUID operationId = UUID.randomUUID();
            OutboxAdminCommand original = new OutboxAdminCommand(operationId, id, 0, "original reason", ACTOR);
            await(service.retry(original));
            Map<String, Object> rowAfter = fixture.outboxRow(id);
            Map<String, Object> otherRow = fixture.outboxRow(other);

            List<Future<OutboxAdminReceipt>> reuse = List.of(
                    service.retry(new OutboxAdminCommand(
                            operationId, id, 0, "original reason", new OutboxAdminActor("user", "mallory"))),
                    service.retry(new OutboxAdminCommand(
                            operationId, id, 0, "original reason", new OutboxAdminActor("service", "alice"))),
                    service.retry(new OutboxAdminCommand(operationId, id, 0, "another reason", ACTOR)),
                    service.retry(new OutboxAdminCommand(operationId, id, 0, "original reason ", ACTOR)),
                    service.retry(new OutboxAdminCommand(operationId, other, 0, "original reason", ACTOR)),
                    service.retry(new OutboxAdminCommand(operationId, id, 1, "original reason", ACTOR)),
                    service.discard(new OutboxAdminCommand(operationId, id, 0, "original reason", ACTOR)));

            for (Future<OutboxAdminReceipt> attempt : reuse) {
                Throwable failure = failureOf(attempt);
                assertConflict(Reason.OPERATION_ID_CONFLICT, failure);
                String message = failure.getMessage();
                assertFalse(message.contains("original"), message);
                assertFalse(message.contains("alice"), message);
            }
            assertEquals(rowAfter, fixture.outboxRow(id));
            assertEquals(otherRow, fixture.outboxRow(other));
            assertEquals(1, fixture.receipts());
        }
    }

    // --- rollback ---

    @Nested
    @DisplayName("atomicity")
    class AtomicityTests {

        private static final String FAIL_ACTOR = "fail-on-completion";

        /**
         * The test-only failure: a check constraint that no insert can violate (a reservation has no
         * disposition) and the completion update violates for one actor id. It breaks the transaction between
         * the state change and the receipt, with no change to the code under test.
         */
        private void installCompletionTrap() throws SQLException {
            fixture.exec("ALTER TABLE outbox_admin_operations ADD CONSTRAINT zz_trap CHECK"
                    + " (disposition IS NULL OR actor_id <> '" + FAIL_ACTOR + "')");
        }

        private void removeCompletionTrap() throws SQLException {
            fixture.exec("ALTER TABLE outbox_admin_operations DROP CONSTRAINT IF EXISTS zz_trap");
        }

        @Test
        @DisplayName("a failure after the state change rolls back the change and the reservation")
        void rollbackLeavesNeither() throws Exception {
            long retryTarget = fixture.seed().insert();
            long discardTarget = fixture.seed().insert();
            Map<String, Object> retryBefore = fixture.outboxRow(retryTarget);
            Map<String, Object> discardBefore = fixture.outboxRow(discardTarget);
            OutboxAdminActor trapped = new OutboxAdminActor("user", FAIL_ACTOR);
            installCompletionTrap();
            try {
                Throwable retryFailure = failureOf(
                        service.retry(new OutboxAdminCommand(UUID.randomUUID(), retryTarget, 0, "reason", trapped)));
                Throwable discardFailure = failureOf(service.discard(
                        new OutboxAdminCommand(UUID.randomUUID(), discardTarget, 0, "reason", trapped)));

                assertInstanceOf(InboxOutboxPersistenceException.class, retryFailure);
                assertInstanceOf(InboxOutboxPersistenceException.class, discardFailure);
                assertFalse(((InboxOutboxPersistenceException) retryFailure).retryable());
                assertFalse(retryFailure.getMessage().contains(FAIL_ACTOR));
            } finally {
                removeCompletionTrap();
            }

            assertEquals(retryBefore, fixture.outboxRow(retryTarget));
            assertEquals(discardBefore, fixture.outboxRow(discardTarget));
            assertEquals(0, fixture.receipts());
        }

        @Test
        @DisplayName("after a rolled-back attempt the same operation id proceeds as a new command")
        void operationIdIsReusableAfterRollback() throws Exception {
            long id = fixture.seed().insert();
            UUID operationId = UUID.randomUUID();
            installCompletionTrap();
            try {
                failureOf(service.retry(new OutboxAdminCommand(
                        operationId, id, 0, "reason", new OutboxAdminActor("user", FAIL_ACTOR))));
            } finally {
                removeCompletionTrap();
            }

            OutboxAdminReceipt receipt = await(service.retry(
                    new OutboxAdminCommand(operationId, id, 0, "reason", new OutboxAdminActor("u", "a"))));

            assertEquals(operationId, receipt.operationId());
            assertEquals("PENDING", fixture.stateOf(id));
            assertEquals(1, fixture.receipts());
        }
    }

    // --- concurrency barriers ---

    @Nested
    @DisplayName("concurrent commands")
    class ConcurrencyTests {

        @Test
        @DisplayName("two commands with one operation id change the row once and both get the same receipt")
        void concurrentDuplicates() throws Exception {
            long id = fixture.seed().insert();
            OutboxAdminCommand command = command(id, 0);
            Future<OutboxAdminReceipt> first;
            Future<OutboxAdminReceipt> second;
            try (Connection blocker = fixture.begin()) {
                fixture.exec(blocker, "SELECT id FROM outbox WHERE id = ? FOR UPDATE", id);

                first = service.retry(command);
                second = service.retry(command);
                // one waits for the entry lock the test holds, the other for the first one's reservation
                fixture.awaitLockWaiters(2);
                blocker.commit();
            }

            OutboxAdminReceipt a = await(first);
            OutboxAdminReceipt b = await(second);

            assertEquals(a, b);
            assertEquals(1, fixture.revisionOf(id), "exactly one revision increment");
            assertEquals(1, fixture.receipts());
        }

        @Test
        @DisplayName("two distinct retries at one revision: exactly one succeeds")
        void distinctRetriesSameRevision() throws Exception {
            long id = fixture.seed().insert();
            Future<OutboxAdminReceipt> first;
            Future<OutboxAdminReceipt> second;
            try (Connection blocker = fixture.begin()) {
                fixture.exec(blocker, "SELECT id FROM outbox WHERE id = ? FOR UPDATE", id);
                first = service.retry(command(id, 0));
                second = service.retry(command(id, 0));
                fixture.awaitLockWaiters(2);
                blocker.commit();
            }

            List<Future<OutboxAdminReceipt>> outcomes = List.of(first, second);
            awaitSettled(outcomes);

            assertEquals(1, outcomes.stream().filter(Future::succeeded).count());
            Throwable loser = outcomes.stream()
                    .filter(Future::failed)
                    .findFirst()
                    .orElseThrow()
                    .cause();
            assertConflict(Reason.NOT_DEAD_LETTER, loser);
            assertEquals(1, fixture.revisionOf(id));
            assertEquals(1, fixture.receipts(), "the rejected command leaves no receipt");
        }

        @Test
        @DisplayName("a retry and a discard racing for one revision: exactly one succeeds")
        void retryVersusDiscard() throws Exception {
            long id = fixture.seed().insert();
            Future<OutboxAdminReceipt> retry;
            Future<OutboxAdminReceipt> discard;
            try (Connection blocker = fixture.begin()) {
                fixture.exec(blocker, "SELECT id FROM outbox WHERE id = ? FOR UPDATE", id);
                retry = service.retry(command(id, 0));
                discard = service.discard(command(id, 0));
                fixture.awaitLockWaiters(2);
                blocker.commit();
            }

            awaitSettled(List.of(retry, discard));

            assertEquals(
                    1,
                    List.of(retry, discard).stream().filter(Future::succeeded).count());
            assertEquals(1, fixture.receipts());
            if (retry.succeeded()) {
                assertEquals("PENDING", fixture.stateOf(id));
                assertConflict(Reason.NOT_DEAD_LETTER, discard.cause());
            } else {
                assertFalse(fixture.outboxExists(id));
                assertInstanceOf(OutboxDeadLetterNotFoundException.class, retry.cause());
            }
        }

        private void awaitSettled(List<Future<OutboxAdminReceipt>> futures) throws Exception {
            for (Future<OutboxAdminReceipt> future : futures) {
                try {
                    await(future);
                } catch (java.util.concurrent.ExecutionException expectedForTheLoser) {
                    // inspected by the caller
                }
            }
        }
    }

    // --- history ---

    @Nested
    @DisplayName("history")
    class HistoryTests {

        private void insertReceipt(UUID operationId, long entryId, Instant recordedAt, boolean complete)
                throws SQLException {
            fixture.exec(
                    "INSERT INTO outbox_admin_operations (operation_id, action, entry_id, expected_revision, reason,"
                            + " actor_type, actor_id, recorded_at, attempt, max_attempts, error_type,"
                            + " destination_type, destination, event_type, disposition, result_revision)"
                            + " VALUES (?, 'RETRY', ?, 0, 'r', 'user', 'alice', ?, 1, 2, 'E', 'SERVICE', 'd', 'e',"
                            + " ?, ?)",
                    operationId,
                    entryId,
                    recordedAt,
                    complete ? "REQUEUED" : null,
                    complete ? 1L : null);
        }

        private List<UUID> expectedOrder(long entryId) throws SQLException {
            List<UUID> order = new ArrayList<>();
            try (Connection connection = fixture.connect();
                    var statement = connection.prepareStatement("SELECT operation_id FROM outbox_admin_operations"
                            + " WHERE entry_id = ? AND disposition IS NOT NULL"
                            + " ORDER BY recorded_at DESC, operation_id DESC")) {
                statement.setLong(1, entryId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        order.add(rows.getObject(1, UUID.class));
                    }
                }
            }
            return order;
        }

        @Test
        @DisplayName("pages newest first with a total order over tied timestamps, excluding other entries and "
                + "incomplete rows")
        void orderingAndPaging() throws Exception {
            Instant tie = BASE.plusSeconds(50);
            for (int i = 0; i < 5; i++) {
                insertReceipt(UUID.randomUUID(), 7, tie, true);
            }
            insertReceipt(UUID.randomUUID(), 7, BASE.plusSeconds(10), true);
            insertReceipt(UUID.randomUUID(), 7, BASE.plusSeconds(90), true);
            insertReceipt(UUID.randomUUID(), 7, BASE.plusSeconds(95), false);
            insertReceipt(UUID.randomUUID(), 8, tie, true);
            List<UUID> expected = expectedOrder(7);
            assertEquals(7, expected.size());

            for (int pageSize : new int[] {1, 2, 3, 7, 50}) {
                List<UUID> walked = new ArrayList<>();
                Optional<OutboxAdminHistoryKey> after = Optional.empty();
                int pages = 0;
                do {
                    OutboxAdminHistoryPage page =
                            await(service.history(new OutboxAdminHistoryQuery(7, pageSize, after)));
                    page.items().forEach(item -> walked.add(item.operationId()));
                    after = page.next();
                    if (after.isPresent()) {
                        OutboxAdminHistoryEntry last =
                                page.items().get(page.items().size() - 1);
                        assertEquals(last.recordedAt(), after.get().recordedAt());
                        assertEquals(last.operationId(), after.get().operationId());
                    }
                    pages++;
                } while (after.isPresent() && pages < 20);
                assertEquals(expected, walked, "page size " + pageSize);
            }
        }

        @Test
        @DisplayName("history records each command and survives the deletion of the entry")
        void historySurvivesDiscard() throws Exception {
            long id = fixture.seed().revision(2).attempts(4, 6).insert();
            OutboxAdminCommand retry = command(id, 2);
            await(service.retry(retry));
            fixture.exec(
                    "UPDATE outbox SET state = 'DEAD_LETTER', attempt = 2, last_error = 'second', error_type = 'E2',"
                            + " updated_at = ? WHERE id = ?",
                    BASE.plusSeconds(2000),
                    id);
            OutboxAdminCommand discard = new OutboxAdminCommand(
                    UUID.randomUUID(), id, 3, "giving up", new OutboxAdminActor("service", "janitor"));
            await(service.discard(discard));
            assertFalse(fixture.outboxExists(id));

            List<OutboxAdminHistoryEntry> items =
                    await(service.history(OutboxAdminHistoryQuery.of(id))).items();

            assertEquals(2, items.size());
            OutboxAdminHistoryEntry newest = items.get(0);
            OutboxAdminHistoryEntry oldest = items.get(1);
            assertTrue(!newest.recordedAt().isBefore(oldest.recordedAt()));
            OutboxAdminHistoryEntry discardEntry = newest.operationId().equals(discard.operationId()) ? newest : oldest;
            OutboxAdminHistoryEntry retryEntry = discardEntry == newest ? oldest : newest;
            assertEquals(OutboxAdminAction.DISCARD, discardEntry.action());
            assertEquals(OutboxAdminDisposition.DISCARDED, discardEntry.disposition());
            assertEquals(3, discardEntry.previousRevision());
            assertEquals(OptionalLong.empty(), discardEntry.resultRevision());
            assertEquals(new OutboxAdminActor("service", "janitor"), discardEntry.actor());
            assertEquals("giving up", discardEntry.reason());
            assertEquals(2, discardEntry.attempt());
            assertEquals(6, discardEntry.maxAttempts());
            assertEquals("E2", discardEntry.errorType());
            assertEquals(DestinationType.SERVICE, discardEntry.destinationType());
            assertEquals("orders.target", discardEntry.destination());
            assertEquals("order.created", discardEntry.eventType());
            assertEquals("Order", discardEntry.aggregateType());
            assertEquals("order-1", discardEntry.aggregateId());
            assertEquals(OutboxAdminAction.RETRY, retryEntry.action());
            assertEquals(OptionalLong.of(3), retryEntry.resultRevision());
            assertEquals(REASON, retryEntry.reason());
            assertEquals(4, retryEntry.attempt());
            assertEquals("java.io.IOException", retryEntry.errorType());
            assertNotEquals(retryEntry.operationId(), discardEntry.operationId());
        }

        @Test
        @DisplayName("history of an entry without receipts is an empty page")
        void emptyHistory() throws Exception {
            OutboxAdminHistoryPage page = await(service.history(OutboxAdminHistoryQuery.of(12345)));

            assertTrue(page.items().isEmpty());
            assertTrue(page.next().isEmpty());
        }
    }

    // --- receipt retention ---

    @Nested
    @DisplayName("receipt retention")
    class RetentionTests {

        @Test
        @DisplayName("old receipts are deleted in bounded batches and recent ones stay")
        void batchedRetention() throws Exception {
            for (int i = 0; i < 5; i++) {
                insertReceiptAt(BASE.minusSeconds(86_400L * 200 + i));
            }
            Instant recent = Instant.now().minusSeconds(3600);
            insertReceiptAt(recent);
            insertReceiptAt(Instant.now());

            assertEquals(2, await(service.cleanupOperations(90, 2)));
            assertEquals(5, fixture.receipts());
            assertEquals(2, await(service.cleanupOperations(90, 2)));
            assertEquals(1, await(service.cleanupOperations(90, 2)));
            assertEquals(0, await(service.cleanupOperations(90, 2)));
            assertEquals(2, fixture.receipts());
            assertEquals(
                    2,
                    fixture.longValue(
                            "SELECT count(*) FROM outbox_admin_operations WHERE recorded_at > now() - interval '1 day'"));
        }

        @Test
        @DisplayName("a non-positive window or batch is rejected before any delete")
        void rejectsNonPositiveArguments() throws Exception {
            insertReceiptAt(BASE);

            assertInstanceOf(ValidationException.class, failureOf(service.cleanupOperations(0, 10)));
            assertInstanceOf(ValidationException.class, failureOf(service.cleanupOperations(-5, 10)));
            assertInstanceOf(ValidationException.class, failureOf(service.cleanupOperations(90, 0)));
            assertEquals(1, fixture.receipts());
        }

        private void insertReceiptAt(Instant recordedAt) throws SQLException {
            fixture.exec(
                    "INSERT INTO outbox_admin_operations (operation_id, action, entry_id, expected_revision, reason,"
                            + " actor_type, actor_id, recorded_at, disposition) VALUES (?, 'DISCARD', 1, 0, 'r', 'u',"
                            + " 'a', ?, 'DISCARDED')",
                    UUID.randomUUID(),
                    recordedAt);
        }
    }

    // --- facade validation ---

    @Test
    @DisplayName("null and non-positive arguments fail with ValidationException before any SQL")
    void facadeValidation() {
        assertInstanceOf(ValidationException.class, failureOf(service.list(null)));
        assertInstanceOf(ValidationException.class, failureOf(service.find(0)));
        assertInstanceOf(ValidationException.class, failureOf(service.readContent(-1)));
        assertInstanceOf(ValidationException.class, failureOf(service.retry(null)));
        assertInstanceOf(ValidationException.class, failureOf(service.discard(null)));
        assertInstanceOf(ValidationException.class, failureOf(service.history(null)));
    }
}
