// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.job.JobRepository;
import dev.vertique.job.cron.config.CronConfig;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CronJobRegistrar} — annotation-based and config-only job registration off the
 * typed {@link CronConfig}, target reference derivation ({@link CronTargetReference.ServiceTarget}
 * vs {@link CronTargetReference.EventBusTarget}), the annotation-override vs config-only distinction,
 * {@code maxAttempts} sourcing (config for config-only jobs; annotation for annotated jobs), removal
 * of the deprecated {@code handler} field, and validation error collection.
 */
@DisplayName("CronJobRegistrar")
class CronJobRegistrarTest {

    // --- Service contract fixtures ---

    /** Contract interface with {@link ServiceOperation}-annotated methods for stable targets. */
    @ServiceContract(value = "report-service", namespace = "reporting")
    interface ReportService {
        @ServiceOperation("generate")
        Future<Void> generate();
    }

    /** Contract interface with a method that has NO {@link ServiceOperation} annotation. */
    @ServiceContract(value = "legacy-service")
    interface LegacyService {
        Future<Void> process();
    }

    // --- Implementation fixtures ---

    static class ReportServiceImpl implements ReportService {
        @CronJob(
                id = "daily-report",
                cron = "0 0 8 * * *",
                timezone = "UTC",
                mode = ExecutionMode.EVERY_INSTANCE,
                overlapPolicy = OverlapPolicy.SKIP)
        @Override
        public Future<Void> generate() {
            return Future.succeededFuture();
        }
    }

    /**
     * Annotated impl whose {@code @CronJob.maxAttempts} is a non-default value so a test can prove
     * the registrar takes maxAttempts from the annotation, not from config.
     */
    @ServiceContract(value = "retry-report-service", namespace = "reporting")
    interface RetryReportService {
        @ServiceOperation("generate")
        Future<Void> generate();
    }

    static class RetryReportServiceImpl implements RetryReportService {
        @CronJob(id = "retry-report", cron = "0 0 8 * * *", timezone = "UTC", maxAttempts = 9)
        @Override
        public Future<Void> generate() {
            return Future.succeededFuture();
        }
    }

    static class LegacyServiceImpl implements LegacyService {
        @CronJob(id = "legacy-process", cron = "0 * * * * *", timezone = "UTC")
        @Override
        public Future<Void> process() {
            return Future.succeededFuture();
        }
    }

    // --- Helpers ---

    /**
     * Returns a lenient {@link ConfigParser} for use in test call-sites that need to parse config.
     *
     * @return a {@link DefaultConfigParser} with lenient mapper
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Parses a raw root config JSON into the typed {@link CronConfig} the registrar now consumes.
     *
     * @param root the raw root config (the {@code cron} subtree is navigated and parsed)
     * @return the typed config
     */
    private static CronConfig cronConfig(JsonObject root) {
        return CronConfig.fromConfig(root, configParser());
    }

    /**
     * The empty typed config (no jobs, global {@code tracked=true}).
     *
     * @return an empty {@link CronConfig}
     */
    private static CronConfig emptyConfig() {
        return CronConfig.fromConfig(new JsonObject(), configParser());
    }

    /**
     * Returns a stub {@link ServiceTargetResolver} that resolves any target id to itself.
     */
    private static ServiceTargetResolver stubTargetResolver() {
        ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
        when(resolver.resolve(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return new ResolvedServiceTarget(id, null, "", id, id, null, id);
        });
        return resolver;
    }

    /**
     * A capturing scheduler that records jobs registered via {@link #register} without
     * needing a live Vert.x instance. Extends {@link CronScheduler} and overrides
     * {@link #register} to collect definitions for test assertions.
     */
    private static class CapturingScheduler extends CronScheduler {

        final List<CronJobDefinition> registered = new ArrayList<>();

        CapturingScheduler(ServiceTargetResolver resolver) {
            super(null, Set.of(), null, resolver, null, dev.vertique.context.DispatchEnvelopeBuilder.forTesting());
        }

        @Override
        public void register(CronJobDefinition job) {
            registered.add(job);
        }
    }

    // --- Annotation-based job tests ---

    @Nested
    @DisplayName("annotation-discovered jobs")
    class AnnotationDiscoveredJobs {

        @Test
        @DisplayName("operation with @ServiceOperation gets ServiceTarget")
        void annotatedOperationGetsServiceTarget() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new ReportServiceImpl()), configParser());
            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);

            new CronJobRegistrar(scheduler, registry, resolver, emptyConfig(), null).scan();

            assertEquals(1, scheduler.registered.size());
            CronJobDefinition def = scheduler.registered.get(0);
            assertEquals("daily-report", def.id());

            CronTargetReference.ServiceTarget st =
                    assertInstanceOf(CronTargetReference.ServiceTarget.class, def.target());
            assertEquals("reporting.report-service.generate", st.stableTargetId());
            assertNull(def.handlerAddress(), "handlerAddress must be null for ServiceTarget");
            assertEquals(
                    "service:reporting.report-service.generate", def.target().toCanonical());
        }

        @Test
        @DisplayName("operation without @ServiceOperation is rejected — cron requires stable target")
        void unannotatedOperationIsRejected() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new LegacyServiceImpl()), configParser());
            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);

            CronJobRegistrar registrar = new CronJobRegistrar(scheduler, registry, resolver, emptyConfig(), null);
            CronRegistrationException ex = assertThrows(CronRegistrationException.class, registrar::scan);
            assertTrue(
                    ex.getMessage().contains("@ServiceOperation"),
                    "Error should mention missing @ServiceOperation: " + ex.getMessage());
        }

        @Test
        @DisplayName("config entry overrides the annotation's enabled/cron/timezone/mode/overlapPolicy/tracked")
        void annotationOverrideFields() {
            // ReportServiceImpl annotation: cron='0 0 8 * * *', tz=UTC, mode=EVERY_INSTANCE,
            // overlapPolicy=SKIP. The config entry overrides every overridable field.
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "daily-report",
                                                            new JsonObject()
                                                                    .put("cron", "0 0 9 * * *")
                                                                    .put("timezone", "Europe/Helsinki")
                                                                    .put("overlapPolicy", "QUEUE_ONE")
                                                                    .put("tracked", false))));

            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new ReportServiceImpl()), configParser());
            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);

            new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan();

            assertEquals(1, scheduler.registered.size());
            CronJobDefinition def = scheduler.registered.get(0);
            assertEquals("daily-report", def.id());
            assertEquals("0 0 9 * * *", def.cronExpression().expression());
            assertEquals("Europe/Helsinki", def.timezone().getId());
            assertEquals(OverlapPolicy.QUEUE_ONE, def.overlapPolicy());
            assertEquals(false, def.tracked());
        }

        @Test
        @DisplayName("annotated job's maxAttempts comes from the annotation, not overridable by config")
        void annotatedMaxAttemptsFromAnnotation() {
            // RetryReportServiceImpl annotation declares maxAttempts=9. A config override entry
            // setting maxAttempts=2 must be ignored for an annotated job.
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put("retry-report", new JsonObject().put("maxAttempts", 2))));

            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new RetryReportServiceImpl()), configParser());
            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);

            new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan();

            assertEquals(1, scheduler.registered.size());
            assertEquals(9, scheduler.registered.get(0).maxAttempts(), "maxAttempts must come from the annotation");
        }
    }

    // --- Config-only job tests ---

    @Nested
    @DisplayName("config-only jobs")
    class ConfigOnlyJobs {

        @Test
        @DisplayName("target field with service: scheme creates ServiceTarget")
        void targetFieldServiceSchemeCreatesServiceTarget() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "config-service-job",
                                                            new JsonObject()
                                                                    .put(
                                                                            "target",
                                                                            "service:reporting.report-service.generate")
                                                                    .put("cron", "0 0 8 * * *"))));

            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());

            new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan();

            assertEquals(1, scheduler.registered.size());
            CronJobDefinition def = scheduler.registered.get(0);
            assertEquals("config-service-job", def.id());

            CronTargetReference.ServiceTarget st =
                    assertInstanceOf(CronTargetReference.ServiceTarget.class, def.target());
            assertEquals("reporting.report-service.generate", st.stableTargetId());
            assertNull(def.handlerAddress());
        }

        @Test
        @DisplayName("target field with eventbus: scheme creates EventBusTarget")
        void targetFieldEventBusSchemeCreatesEventBusTarget() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "config-eventbus-job",
                                                            new JsonObject()
                                                                    .put(
                                                                            "target",
                                                                            "eventbus:myapp/reporting/generateReport")
                                                                    .put("cron", "0 0 8 * * *"))));

            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());

            new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan();

            assertEquals(1, scheduler.registered.size());
            CronJobDefinition def = scheduler.registered.get(0);
            assertEquals("config-eventbus-job", def.id());

            CronTargetReference.EventBusTarget et =
                    assertInstanceOf(CronTargetReference.EventBusTarget.class, def.target());
            assertEquals("myapp/reporting/generateReport", et.address());
            assertEquals("myapp/reporting/generateReport", def.handlerAddress());
        }

        @Test
        @DisplayName("config-only job reads maxAttempts from config")
        void configOnlyMaxAttemptsFromConfig() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "config-max-job",
                                                            new JsonObject()
                                                                    .put("target", "eventbus:m/addr")
                                                                    .put("cron", "0 0 8 * * *")
                                                                    .put("maxAttempts", 5))));

            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());

            new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan();

            assertEquals(1, scheduler.registered.size());
            assertEquals(5, scheduler.registered.get(0).maxAttempts());
        }

        @Test
        @DisplayName("removed handler field: an entry with only handler and no target is skipped")
        void handlerRemovedEntryWithoutTargetIsSkipped() {
            // The deprecated 'handler' field is removed. An entry carrying only 'handler' (no
            // 'target') no longer registers a config-only job — it is treated as an override-only
            // entry for a (non-existent) annotation, and skipped.
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "legacy-config-job",
                                                            new JsonObject()
                                                                    .put("handler", "myapp/reporting/generateReport")
                                                                    .put("cron", "0 0 8 * * *"))));

            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());

            new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan();

            assertEquals(0, scheduler.registered.size(), "handler-only entry must not register a job");
        }

        @Test
        @DisplayName("entry with neither target nor matching annotation is skipped (override-only config)")
        void entryWithoutTargetIsSkipped() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "override-only",
                                                            new JsonObject().put("cron", "0 0 9 * * *"))));

            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());

            new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan();

            assertEquals(0, scheduler.registered.size());
        }

        @Test
        @DisplayName("invalid target scheme produces validation violation")
        void invalidTargetSchemeProducesViolation() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "bad-scheme-job",
                                                            new JsonObject()
                                                                    .put("target", "handler:old.address")
                                                                    .put("cron", "0 0 8 * * *"))));

            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());

            CronRegistrationException ex = assertThrows(
                    CronRegistrationException.class,
                    () -> new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan());
            assertEquals(1, ex.violations().size());
            String violation = ex.violations().get(0);
            assert violation.contains("bad-scheme-job") : "Violation should mention job id: " + violation;
            assert violation.contains("target") : "Violation should mention target: " + violation;
        }

        @Test
        @DisplayName("disabled config-only job is not registered")
        void disabledConfigJobIsSkipped() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "disabled-job",
                                                            new JsonObject()
                                                                    .put("target", "eventbus:some/address")
                                                                    .put("cron", "0 0 8 * * *")
                                                                    .put("enabled", false))));

            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());

            new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan();

            assertEquals(0, scheduler.registered.size());
        }
    }

    // --- Malformed-config validation (now detected at the typed boundary) ---

    @Nested
    @DisplayName("malformed cron config")
    class MalformedCronConfig {

        @Test
        @DisplayName("scalar at 'cron' fails fast at the boundary with a path-bearing message")
        void scalarAtCronSectionProducesViolation() {
            JsonObject root = new JsonObject().put("cron", "oops");

            ConfigurationException ex = assertThrows(ConfigurationException.class, () -> cronConfig(root));
            assertTrue(
                    ex.getMessage().contains("'cron'") && ex.getMessage().contains("JSON object"),
                    "Expected a 'cron must be a JSON object' message, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("scalar at 'cron.jobs' fails fast at the boundary with a path-bearing message")
        void scalarAtCronJobsProducesViolation() {
            JsonObject root = new JsonObject().put("cron", new JsonObject().put("jobs", "oops"));

            ConfigurationException ex = assertThrows(ConfigurationException.class, () -> cronConfig(root));
            assertTrue(
                    ex.getMessage().contains("'jobs'") && ex.getMessage().contains("JSON object"),
                    "Expected a 'jobs must be a JSON object' message, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("scalar at 'cron.jobs.<jobId>' fails fast at the boundary with the full path")
        void scalarAtPerJobOverrideProducesViolation() {
            JsonObject root = new JsonObject()
                    .put("cron", new JsonObject().put("jobs", new JsonObject().put("daily-report", "oops")));

            ConfigurationException ex = assertThrows(ConfigurationException.class, () -> cronConfig(root));
            assertTrue(
                    ex.getMessage().contains("'cron.jobs.daily-report'")
                            && ex.getMessage().contains("JSON object"),
                    "Expected per-job 'must be a JSON object' message with full path, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("absent cron section is fine — no violation, nothing registered")
        void absentCronSectionIsFine() {
            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());

            new CronJobRegistrar(scheduler, registry, resolver, emptyConfig(), null).scan();
            assertEquals(0, scheduler.registered.size());
        }
    }

    // --- Transactional scan: validate-then-register (no partial scheduler state on failure) ---

    /**
     * A {@link CronJob}-annotated impl with no {@link ServiceOperation} — guaranteed to produce a
     * single validation violation (handler must have a stable service target).
     */
    static class InvalidJobImpl implements LegacyService {
        @CronJob(id = "invalid-job", cron = "0 0 9 * * *", timezone = "UTC")
        @Override
        public Future<Void> process() {
            return Future.succeededFuture();
        }
    }

    // --- Exception hierarchy ---

    @Nested
    @DisplayName("exception hierarchy")
    class ExceptionHierarchy {

        @Test
        @DisplayName("CronRegistrationException is a ConfigurationException")
        void cronRegistrationExceptionIsConfigurationException() {
            CronRegistrationException ex = new CronRegistrationException(List.of("v"));
            assertInstanceOf(ConfigurationException.class, ex);
        }
    }

    @Nested
    @DisplayName("transactional scan")
    class TransactionalScan {

        @Test
        @DisplayName("scan with mixed valid + invalid jobs throws and registers nothing")
        void mixedValidAndInvalidThrowsAndRegistersNothing() {
            ServiceContractRegistry registry = ServiceContractRegistry.build(
                    Set.of(new ReportServiceImpl(), new InvalidJobImpl()), configParser());
            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);

            CronJobRegistrar registrar = new CronJobRegistrar(scheduler, registry, resolver, emptyConfig(), null);

            assertThrows(CronRegistrationException.class, registrar::scan);
            assertEquals(
                    0,
                    scheduler.registered.size(),
                    "scheduler must not see any registrations when validation fails — "
                            + "leaves no partial state for the lifecycle verticle to undeploy around");
        }

        @Test
        @DisplayName("synchronous repository.saveSchedule() throw is contained — scan() succeeds")
        void persistScheduleSyncThrowDoesNotEscape() {
            // A misbehaving JobRepository that throws synchronously from saveSchedule(...) (e.g.
            // NPE in a custom impl, IllegalStateException from a closed pool) must not escape
            // scan() — schedule persistence is documented as fire-and-forget and the lifecycle
            // verticle relies on scan() succeeding once registration is complete. If this throw
            // escaped, all jobs would be registered with the scheduler but scheduler.start() would
            // never be called.
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new ReportServiceImpl()), configParser());
            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);
            JobRepository repository = mock(JobRepository.class);
            when(repository.saveSchedule(any())).thenThrow(new RuntimeException("repo boom"));

            new CronJobRegistrar(scheduler, registry, resolver, emptyConfig(), repository).scan();

            assertEquals(1, scheduler.registered.size(), "registration must complete despite persist failure");
        }

        @Test
        @DisplayName("scan with all-valid jobs registers every definition exactly once")
        void allValidRegistersEvery() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new ReportServiceImpl()), configParser());
            ServiceTargetResolver resolver = stubTargetResolver();
            CapturingScheduler scheduler = new CapturingScheduler(resolver);

            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "config-job",
                                                            new JsonObject()
                                                                    .put("target", "eventbus:cfg/address")
                                                                    .put("cron", "0 0 8 * * *"))));

            new CronJobRegistrar(scheduler, registry, resolver, cronConfig(root), null).scan();

            assertEquals(2, scheduler.registered.size());
            assertTrue(scheduler.registered.stream().anyMatch(d -> d.id().equals("daily-report")));
            assertTrue(scheduler.registered.stream().anyMatch(d -> d.id().equals("config-job")));
        }
    }
}
