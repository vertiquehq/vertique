// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.JobCompletionHandler;
import dev.vertique.job.JobContext;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobState;
import dev.vertique.job.delayed.config.DelayedJobQueueConfig;
import dev.vertique.job.postgresql.PgJobRepository;
import dev.vertique.logging.MDCContexts;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.ServiceHandler;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Drives a delayed job from {@link DelayedJobService#enqueue(DelayedJob)} through PostgreSQL, the
 * real {@link DelayedJobPoller} and {@link ServiceVerticle} to the handler, so the handler receives
 * the type it declares and the execution ends {@link JobState#SUCCEEDED}.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Delayed job payload delivery")
class DelayedJobPayloadDeliveryIT {

    private static final int AWAIT_SECONDS = 10;

    static final PostgresContainer db =
            new PostgresContainer().withDatabaseName("delayed_job_test").withMigration("classpath:db/migration/job");

    /** Payloads each handler received, by handler name. */
    private static final Map<String, CompletableFuture<Object>> received = new ConcurrentHashMap<>();

    private static Pool pool;
    private static PgJobRepository repository;
    private static DelayedJobService service;

    // --- Payload and handler types ---

    record WelcomePayload(String email, int attempt) {}

    @DelayedJobContract(name = "typed-record")
    interface RecordJob extends DelayedJobClient<WelcomePayload> {}

    static final class RecordJobExecutor implements DelayedJobExecutor<WelcomePayload, RecordJob> {
        @Override
        public Future<Void> execute(WelcomePayload payload, JobContext ctx) {
            return record("typed-record", payload);
        }
    }

    @DelayedJobContract(name = "typed-json-object")
    interface JsonObjectJob extends DelayedJobClient<JsonObject> {}

    static final class JsonObjectJobExecutor implements DelayedJobExecutor<JsonObject, JsonObjectJob> {
        @Override
        public Future<Void> execute(JsonObject payload, JobContext ctx) {
            return record("typed-json-object", payload);
        }
    }

    enum Tier {
        FREE,
        PAID
    }

    @DelayedJobContract(name = "typed-string")
    interface StringJob extends DelayedJobClient<String> {}

    static final class StringJobExecutor implements DelayedJobExecutor<String, StringJob> {
        @Override
        public Future<Void> execute(String payload, JobContext ctx) {
            return record("typed-string", payload);
        }
    }

    @DelayedJobContract(name = "typed-long")
    interface LongJob extends DelayedJobClient<Long> {}

    static final class LongJobExecutor implements DelayedJobExecutor<Long, LongJob> {
        @Override
        public Future<Void> execute(Long payload, JobContext ctx) {
            return record("typed-long", payload);
        }
    }

    @DelayedJobContract(name = "typed-uuid")
    interface UuidJob extends DelayedJobClient<UUID> {}

    static final class UuidJobExecutor implements DelayedJobExecutor<UUID, UuidJob> {
        @Override
        public Future<Void> execute(UUID payload, JobContext ctx) {
            return record("typed-uuid", payload);
        }
    }

    @DelayedJobContract(name = "typed-enum")
    interface EnumJob extends DelayedJobClient<Tier> {}

    static final class EnumJobExecutor implements DelayedJobExecutor<Tier, EnumJob> {
        @Override
        public Future<Void> execute(Tier payload, JobContext ctx) {
            return record("typed-enum", payload);
        }
    }

    @DelayedJobContract(name = "typed-list")
    interface ListJob extends DelayedJobClient<List<UUID>> {}

    static final class ListJobExecutor implements DelayedJobExecutor<List<UUID>, ListJob> {
        @Override
        public Future<Void> execute(List<UUID> payload, JobContext ctx) {
            return record("typed-list", payload);
        }
    }

    @ServiceContract(namespace = "payload-test", value = "welcome")
    interface WelcomeService {
        @ServiceOperation("send")
        Future<Void> send(WelcomePayload payload);
    }

    static final class WelcomeServiceHandler implements ServiceHandler<WelcomeService> {
        @DelayedJobHandlerMethod("annotated-record")
        public Future<Void> send(WelcomePayload payload, JobContext ctx) {
            return record("annotated-record", payload);
        }
    }

    @ServiceContract(namespace = "payload-test", value = "batch")
    interface BatchService {
        @ServiceOperation("send")
        Future<Void> send(Map<String, UUID> ids);
    }

    static final class BatchServiceHandler implements ServiceHandler<BatchService> {
        @DelayedJobHandlerMethod("annotated-generic")
        public Future<Void> send(Map<String, UUID> ids, JobContext ctx) {
            return record("annotated-generic", ids.get("id"));
        }
    }

    private static Future<Void> record(String handler, Object payload) {
        received.computeIfAbsent(handler, h -> new CompletableFuture<>()).complete(payload);
        return Future.succeededFuture();
    }

    // --- Setup ---

    @BeforeAll
    static void setUp(Vertx vertx) throws Exception {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(5))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        repository = new PgJobRepository(pool, new PgDbExceptionMapper());

        vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));

        Set<Object> executors = Set.of(
                new RecordJobExecutor(),
                new JsonObjectJobExecutor(),
                new StringJobExecutor(),
                new LongJobExecutor(),
                new UuidJobExecutor(),
                new EnumJobExecutor(),
                new ListJobExecutor());
        ServiceContractRegistry registry = ServiceContractRegistry.build(
                Set.of(new WelcomeServiceHandler(), new BatchServiceHandler()),
                Set.of(new DelayedJobContractContributor(executors)),
                new JsonObject(),
                new DefaultConfigParser(DefaultConfigMapper.lenient()));
        DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);
        registrar.scan();

        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
        service = new DelayedJobService(repository, repository, registrar, propagator, new DelayedJobExceptionMapper());

        ServiceDispatchContextRegistry contextRegistry =
                new ServiceDispatchContextRegistry(Set.of(), Set.of(MDCContexts.serviceDispatchDecoder()));
        for (ServiceContractRegistry.ContractEntry<?> entry : registry.entries()) {
            await(vertx.deployVerticle(new ServiceVerticle<>(
                    entry, new ServiceExceptionMapper(), List.of(), null, null, contextRegistry)));
        }
        await(vertx.deployVerticle(new DelayedJobPoller(
                "default",
                DelayedJobQueueConfig.builder()
                        .sleepDelayMs(50L)
                        .maxConcurrentJobs(5)
                        .backoffStrategy("LINEAR")
                        .backoffBaseDelayMs(50L)
                        .backoffMaxDelayMs(50L)
                        .build(),
                repository,
                new JobCompletionHandler(repository),
                Set.of(),
                new EventBusClient(vertx, new EventBusExceptionMapper()),
                0L,
                0L,
                DispatchEnvelopeBuilder.forTesting(),
                propagator,
                registrar.handlerPayloadTypes())));
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Enqueues a payload, waits for the execution to end, and asserts the handler received
     * {@code expected} and the execution succeeded.
     */
    private static void assertDelivered(String handler, Object payload, Object expected) throws Exception {
        CompletableFuture<Object> delivered = received.computeIfAbsent(handler, h -> new CompletableFuture<>());
        JobExecution finished = awaitTerminal(enqueue(handler, payload));
        assertEquals(JobState.SUCCEEDED, finished.state(), () -> "execution failed: " + finished.errorMessage());
        assertEquals(expected, delivered.get(AWAIT_SECONDS, TimeUnit.SECONDS));
    }

    private static UUID enqueue(String handler, Object payload) throws Exception {
        return await(service.enqueue(DelayedJob.builder()
                .handler(handler)
                .payload(payload)
                .maxAttempts(1)
                .metadata(DurableMetadata.empty())
                .build()));
    }

    private static JobExecution awaitTerminal(UUID executionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            JobExecution execution = await(repository.findById(executionId)).orElseThrow();
            if (execution.state() == JobState.SUCCEEDED || execution.state() == JobState.DEAD_LETTER) {
                return execution;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("execution " + executionId + " did not end");
    }

    // --- Tests ---

    @Test
    @DisplayName("a record payload reaches a typed executor as the record")
    void recordReachesTypedExecutor() throws Exception {
        WelcomePayload payload = new WelcomePayload("a@example.com", 2);
        assertDelivered("typed-record", payload, payload);
    }

    @Test
    @DisplayName("a record payload reaches a @DelayedJobHandlerMethod as the record")
    void recordReachesAnnotatedHandler() throws Exception {
        WelcomePayload payload = new WelcomePayload("b@example.com", 3);
        assertDelivered("annotated-record", payload, payload);
    }

    @Test
    @DisplayName("generic arguments of a handler's declared payload type are honored")
    void genericArgumentsAreHonored() throws Exception {
        UUID id = UUID.randomUUID();
        assertDelivered("annotated-generic", new JsonObject().put("id", id.toString()), id);
    }

    @Test
    @DisplayName("a String payload reaches a String executor")
    void stringReachesExecutor() throws Exception {
        assertDelivered("typed-string", "hello", "hello");
    }

    @Test
    @DisplayName("a Long payload small enough to come back as an Integer reaches a Long executor as a Long")
    void longReachesExecutorAsLong() throws Exception {
        assertDelivered("typed-long", 42L, 42L);
    }

    @Test
    @DisplayName("a UUID payload stored as a JSON string reaches a UUID executor as a UUID")
    void uuidReachesExecutorAsUuid() throws Exception {
        UUID payload = UUID.randomUUID();
        assertDelivered("typed-uuid", payload, payload);
    }

    @Test
    @DisplayName("an enum payload reaches an enum executor")
    void enumReachesExecutor() throws Exception {
        assertDelivered("typed-enum", Tier.PAID, Tier.PAID);
    }

    @Test
    @DisplayName("a list payload reaches an executor with its element type")
    void listReachesExecutorWithElementType() throws Exception {
        List<UUID> payload = List.of(UUID.randomUUID(), UUID.randomUUID());
        assertDelivered("typed-list", payload, payload);
    }

    @Test
    @DisplayName("a scalar that does not fit the declared type dead-letters without quoting the value")
    void undecodableScalarFailsWithoutQuotingIt() throws Exception {
        JobExecution finished = awaitTerminal(enqueue("typed-uuid", "secret-value"));

        assertEquals(JobState.DEAD_LETTER, finished.state());
        assertEquals("Job payload cannot be decoded to " + UUID.class.getTypeName(), finished.errorMessage());
    }

    @Test
    @DisplayName("a payload that does not fit the declared type dead-letters without quoting the value")
    void undecodablePayloadFailsWithoutQuotingIt() throws Exception {
        JobExecution finished = awaitTerminal(enqueue(
                "typed-record", new JsonObject().put("email", "d@example.com").put("attempt", "secret-value")));

        assertEquals(JobState.DEAD_LETTER, finished.state());
        assertEquals("Job payload cannot be decoded to " + WelcomePayload.class.getTypeName(), finished.errorMessage());
    }

    @Test
    @DisplayName("a JsonObject payload reaches a JsonObject executor unchanged")
    void jsonObjectReachesJsonObjectExecutor() throws Exception {
        JsonObject payload = new JsonObject().put("email", "c@example.com");
        assertDelivered("typed-json-object", payload, payload);
    }
}
