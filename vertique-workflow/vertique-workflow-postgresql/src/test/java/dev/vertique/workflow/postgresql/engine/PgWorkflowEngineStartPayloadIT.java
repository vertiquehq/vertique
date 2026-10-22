// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for the rejection of caller payload mistakes on the engine start path.
 *
 * <p>A start whose payload is missing for a payload-typed start, present for a start without a
 * payload type, or unbindable to the start type fails with {@link WorkflowInvalidPayloadException}.
 * The failure is value-free, happens inside the start transaction before any instance row is
 * written, rolls back the start idempotency claim so a retry with the same key and a corrected
 * payload succeeds, and never logs the underlying cause above DEBUG. An initial-state function that
 * throws for a valid payload stays a definition failure that chains the application exception as its
 * cause without copying its message.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineStartPayloadIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_start_payload_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    // --- Constants ---

    /** Value placed in payloads that must never be echoed by a failure message, detail or log line. */
    private static final String SENTINEL = "ZZSENTINEL";

    /** Value carried by the exception that the failing initial-state function throws. */
    private static final String INITIALIZER_SENTINEL = "ZZINITIALIZER";

    private static final String TYPED_DEFINITION_ID = "start-payload-typed";
    private static final String VOID_DEFINITION_ID = "start-payload-void";
    private static final String THROWING_DEFINITION_ID = "start-payload-throwing";

    private static final String REQUIRED_MESSAGE = "payload is required";
    private static final String VOID_START_MESSAGE = "payload is not accepted for a start type without a payload";
    private static final String BINDING_MESSAGE = "payload does not bind to the declared type";
    private static final String REJECTION_MESSAGE = "invalid start payload";

    // --- Domain types ---

    /** Start payload of the typed and throwing fixtures. */
    record StartPayload(String requester, int seats) {}

    /** Workflow state of every fixture. */
    record State(String requester) {
        State() {
            this(null);
        }
    }

    interface TypedContract {}

    interface VoidContract {}

    interface ThrowingContract {}

    // --- Workflow definitions ---

    /** Start type {@link StartPayload}; the initial-state function dereferences the payload. */
    static final WorkflowDefinition<State, TypedContract> TYPED_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<TypedContract> contract() {
            return TypedContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return TYPED_DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartPayload.class, p -> new State(p.requester()))
                    .initialStep("done")
                    .complete("done");
        }
    };

    /** Start type {@link Void}: the start accepts only a null payload. */
    static final WorkflowDefinition<State, VoidContract> VOID_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<VoidContract> contract() {
            return VoidContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return VOID_DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Void.class, ignored -> new State()).initialStep("done").complete("done");
        }
    };

    /** Start type {@link StartPayload}; the initial-state function always throws. */
    static final WorkflowDefinition<State, ThrowingContract> THROWING_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<ThrowingContract> contract() {
            return ThrowingContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return THROWING_DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartPayload.class, p -> {
                        throw new IllegalStateException("initializer rejected " + INITIALIZER_SENTINEL);
                    })
                    .initialStep("done")
                    .complete("done");
        }
    };

    // --- Lifecycle ---

    private CapturedLogs logs;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();

        PgDbExceptionMapper ex = new PgDbExceptionMapper();
        PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, ex);
        PgWorkflowHistoryRepository history = new PgWorkflowHistoryRepository(pool, ex);
        PgWorkflowDedupRepository dedup = new PgWorkflowDedupRepository(pool, ex);

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(TYPED_DEF);
        registry.register(VOID_DEF);
        registry.register(THROWING_DEF);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC());
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables() {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances"
                        + " RESTART IDENTITY CASCADE")
                .execute()
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    @AfterEach
    void detachLogs() {
        if (logs != null) {
            logs.close();
            logs = null;
        }
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("an unbindable start payload fails and leaves no instance or dedup row")
    void unbindableStartPayloadFailsAndLeavesNoInstanceOrDedupRow() {
        // Given: a typed start and a payload whose int seats holds the sentinel string, so it cannot bind
        String key = "s1";

        // When: the start is attempted
        WorkflowInvalidPayloadException rejection =
                startExpectingInvalidPayload(TYPED_DEFINITION_ID, unbindableSeats(), key);

        // Then: one binding detail, the decode failure as cause, no payload value anywhere
        assertEquals(REJECTION_MESSAGE, rejection.getMessage());
        assertSingleDetail(rejection, BINDING_MESSAGE, "binding");
        assertInstanceOf(DecodeException.class, rejection.getCause(), "the decode failure must be the cause");
        assertTrue(
                rejection.getCause().getMessage().contains(SENTINEL),
                "precondition: the decode cause message echoes the payload value, so interpolating it would leak");
        assertFalse(rejection.getMessage().contains(SENTINEL), "message must not carry the payload value");
        assertFalse(rejection.violations().toString().contains(SENTINEL), "no detail may carry the payload value");

        // And: neither an instance row nor the start claim remains
        assertEquals(0L, instanceRows(TYPED_DEFINITION_ID), "a rejected start must not create an instance");
        assertEquals(0L, startDedupRows(TYPED_DEFINITION_ID, key), "a rejected start must not retain the claim");

        // When: the same key is retried with a corrected payload
        WorkflowInstanceId created = start(TYPED_DEFINITION_ID, Map.of("requester", "ann", "seats", 2), key);

        // Then: the retry creates the instance and holds the claim
        assertNotNull(created, "the corrected retry must create an instance");
        assertEquals(1L, instanceRows(TYPED_DEFINITION_ID));
        assertEquals(1L, startDedupRows(TYPED_DEFINITION_ID, key), "only the successful attempt may hold the claim");
    }

    @Test
    @DisplayName("a null payload for a non-void start type fails with the invalid-payload exception")
    void nullPayloadForANonVoidStartTypeFailsWithInvalidPayload() {
        // Given: a typed start; When: it is started with a null payload
        String key = "idem-" + UUID.randomUUID();
        WorkflowInvalidPayloadException rejection = startExpectingInvalidPayload(TYPED_DEFINITION_ID, null, key);

        // Then: one payload-required detail (and not a definition exception), no instance, no claim
        assertEquals(REJECTION_MESSAGE, rejection.getMessage());
        assertSingleDetail(rejection, REQUIRED_MESSAGE, "payload");
        assertEquals(0L, instanceRows(TYPED_DEFINITION_ID), "a rejected start must not create an instance");
        assertEquals(0L, startDedupRows(TYPED_DEFINITION_ID, key), "a rejected start must not retain the claim");
    }

    @Test
    @DisplayName("a payload for a void start type fails with the invalid-payload exception")
    void payloadForAVoidStartTypeFailsWithInvalidPayload() {
        // Given: a start type without a payload; When: it is started with a payload
        String rejectedKey = "idem-reject-" + UUID.randomUUID();
        WorkflowInvalidPayloadException rejection =
                startExpectingInvalidPayload(VOID_DEFINITION_ID, Map.of("x", 1), rejectedKey);

        // Then: one payload detail, no instance, no claim
        assertEquals(REJECTION_MESSAGE, rejection.getMessage());
        assertSingleDetail(rejection, VOID_START_MESSAGE, "payload");
        assertEquals(0L, instanceRows(VOID_DEFINITION_ID), "a rejected start must not create an instance");
        assertEquals(0L, startDedupRows(VOID_DEFINITION_ID, rejectedKey), "a rejected start must not retain the claim");

        // When: the same start type is started with a null payload
        WorkflowInstanceId created = start(VOID_DEFINITION_ID, null, "idem-ok-" + UUID.randomUUID());

        // Then: it succeeds and creates an instance
        assertNotNull(created, "a null payload must start a void start type");
        assertEquals(1L, instanceRows(VOID_DEFINITION_ID));
    }

    @Test
    @DisplayName("an initialState throw stays a definition exception that chains the cause")
    void initialStateThrowStaysADefinitionException() {
        // Given: a start whose initial-state function throws for a valid payload
        String key = "idem-" + UUID.randomUUID();

        // When: it is started with a valid payload
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> start(THROWING_DEFINITION_ID, Map.of("requester", "ann", "seats", 2), key));

        // Then: a definition exception (not the new type) naming the definition, not the application text
        WorkflowDefinitionException definitionFailure = assertInstanceOf(
                WorkflowDefinitionException.class,
                failure.getCause(),
                "an initial-state failure stays a definition failure");
        assertTrue(
                definitionFailure.getMessage().contains(THROWING_DEFINITION_ID),
                "message must name the definition, was: " + definitionFailure.getMessage());
        assertFalse(
                definitionFailure.getMessage().contains(INITIALIZER_SENTINEL),
                "message must not copy the application exception message");

        // And: the thrown exception is the cause, and no instance row remains
        IllegalStateException cause = assertInstanceOf(
                IllegalStateException.class,
                definitionFailure.getCause(),
                "the initializer exception must be chained as the cause");
        assertTrue(cause.getMessage().contains(INITIALIZER_SENTINEL));
        assertEquals(0L, instanceRows(THROWING_DEFINITION_ID), "a failed start must not create an instance");
    }

    @Test
    @DisplayName("the rejected start payload cause is never logged above DEBUG")
    void rejectedStartPayloadCauseIsNeverLoggedAboveDebug() {
        // Given: an engine logger capture at INFO
        logs = CapturedLogs.attach(Level.INFO);

        // When: an unbindable start payload is rejected
        WorkflowInvalidPayloadException rejection =
                startExpectingInvalidPayload(TYPED_DEFINITION_ID, unbindableSeats(), "idem-info-" + UUID.randomUUID());

        // Then: no event at INFO or above carries the decode cause or the payload value
        assertInstanceOf(DecodeException.class, rejection.getCause());
        assertTrue(
                rejection.getCause().getMessage().contains(SENTINEL),
                "precondition: the decode cause message echoes the payload value, so interpolating it would leak");
        for (ILoggingEvent event : logs.events()) {
            if (event.getLevel().isGreaterOrEqual(Level.INFO)) {
                assertFalse(
                        CapturedLogs.carriesDecodeException(event),
                        "the decode cause must not be logged at " + event.getLevel());
                assertFalse(
                        event.getFormattedMessage().contains(SENTINEL),
                        "no log line at " + event.getLevel() + " may carry the payload value");
            }
        }

        // When: the same rejection runs with DEBUG enabled
        logs.setLevel(Level.DEBUG);
        logs.clear();
        startExpectingInvalidPayload(TYPED_DEFINITION_ID, unbindableSeats(), "idem-debug-" + UUID.randomUUID());

        // Then: exactly one event carries the decode cause, at DEBUG, and no message carries the value
        List<ILoggingEvent> events = logs.events();
        assertEquals(
                1L,
                events.stream().filter(CapturedLogs::carriesDecodeException).count(),
                "exactly one DEBUG event must carry the decode cause");
        events.stream()
                .filter(CapturedLogs::carriesDecodeException)
                .forEach(event -> assertEquals(Level.DEBUG, event.getLevel(), "the cause is logged at DEBUG only"));
        assertTrue(
                events.stream().noneMatch(event -> event.getFormattedMessage().contains(SENTINEL)),
                "no log line may carry the payload value");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * A payload whose {@code seats} is the sentinel string, so it cannot bind to the {@code int} of
     * {@link StartPayload}; the binder's message echoes the string.
     */
    private static Map<String, Object> unbindableSeats() {
        return Map.of("requester", "ann", "seats", SENTINEL);
    }

    /**
     * Starts the definition through the engine, which owns the transaction, and waits for the result.
     *
     * @param definitionId the definition to start
     * @param payload the raw start payload, may be {@code null}
     * @param key the start idempotency key
     * @return the created instance id; fails with {@link CompletionException} when the start fails
     */
    private static WorkflowInstanceId start(String definitionId, Object payload, String key) {
        return engine.start(new StartCommand(
                        definitionId, contractNameOf(definitionId), payload, key, null, null, null, null, null))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    /**
     * Returns the contract name of a fixture definition.
     *
     * @param definitionId the id of one of this test's fixture definitions
     * @return the name of the definition's contract
     */
    private static String contractNameOf(String definitionId) {
        return switch (definitionId) {
            case TYPED_DEFINITION_ID -> TypedContract.class.getName();
            case VOID_DEFINITION_ID -> VoidContract.class.getName();
            case THROWING_DEFINITION_ID -> ThrowingContract.class.getName();
            default -> throw new IllegalArgumentException("unknown fixture definition: " + definitionId);
        };
    }

    /**
     * Starts the definition and returns the payload rejection the engine fails the future with.
     *
     * @param definitionId the definition to start
     * @param payload the raw start payload, may be {@code null}
     * @param key the start idempotency key
     * @return the rejection; the assertion fails when the future fails with any other type
     */
    private static WorkflowInvalidPayloadException startExpectingInvalidPayload(
            String definitionId, Object payload, String key) {
        CompletionException failure = assertThrows(CompletionException.class, () -> start(definitionId, payload, key));
        return assertInstanceOf(
                WorkflowInvalidPayloadException.class,
                failure.getCause(),
                "start must fail with WorkflowInvalidPayloadException");
    }

    private static void assertSingleDetail(WorkflowInvalidPayloadException rejection, String message, String type) {
        List<ViolationDetail> violations = rejection.violations();
        assertEquals(1, violations.size(), "exactly one detail expected, got " + violations);
        ViolationDetail detail = violations.get(0);
        assertNull(detail.path());
        assertEquals(message, detail.message());
        assertEquals(type, detail.type());
    }

    private static long instanceRows(String definitionId) {
        return count("SELECT count(*) FROM workflow_instances WHERE definition_id = $1", Tuple.of(definitionId));
    }

    private static long startDedupRows(String definitionId, String key) {
        return count(
                "SELECT count(*) FROM workflow_dedup WHERE kind = 'start' AND dedup_namespace = $1 AND key = $2",
                Tuple.of(definitionId, key));
    }

    private static long count(String sql, Tuple params) {
        return pool.preparedQuery(sql)
                .execute(params)
                .map(rs -> rs.iterator().next().getLong(0))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    /**
     * Captures events of the engine logger tree and restores the logger on {@link #close()}.
     */
    static final class CapturedLogs implements AutoCloseable {

        private final Logger logger;
        private final Level originalLevel;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private CapturedLogs(Logger logger, Level level) {
            this.logger = logger;
            this.originalLevel = logger.getLevel();
            logger.setLevel(level);
            appender.start();
            logger.addAppender(appender);
        }

        static CapturedLogs attach(Level level) {
            return new CapturedLogs((Logger) LoggerFactory.getLogger("dev.vertique.workflow.engine"), level);
        }

        void setLevel(Level level) {
            logger.setLevel(level);
        }

        void clear() {
            appender.list.clear();
        }

        List<ILoggingEvent> events() {
            return new ArrayList<>(appender.list);
        }

        /** Whether the event's throwable, or any cause in its chain, is a Vert.x {@link DecodeException}. */
        static boolean carriesDecodeException(ILoggingEvent event) {
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                if (DecodeException.class.getName().equals(proxy.getClassName())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }
    }
}
