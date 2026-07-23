// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.job.JobContext;
import dev.vertique.job.delayed.config.DelayedJobsConfig;
import dev.vertique.services.ServiceContractRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DelayedJobTargetResolver} and {@link DefaultDelayedJobTargetResolver}:
 * resolution by contract class, resolution by target id, config override precedence,
 * annotation defaults, and error cases for unknown contracts and target ids.
 */
@DisplayName("DelayedJobTargetResolver")
class DelayedJobTargetResolverTest {

    // --- Test Fixtures ---

    /** Contract with custom defaults on the annotation. */
    @DelayedJobContract(name = "alpha-job", maxAttempts = 5, queue = "priority", priority = 10)
    interface AlphaJob extends DelayedJobClient<String> {}

    static class AlphaJobExecutor implements DelayedJobExecutor<String, AlphaJob> {
        @Override
        public Future<Void> execute(String payload, JobContext ctx) {
            return Future.succeededFuture();
        }
    }

    /** Contract with annotation defaults (queue="default", priority=0, maxAttempts=3). */
    @DelayedJobContract(name = "beta-job")
    interface BetaJob extends DelayedJobClient<Integer> {}

    static class BetaJobExecutor implements DelayedJobExecutor<Integer, BetaJob> {
        @Override
        public Future<Void> execute(Integer payload, JobContext ctx) {
            return Future.succeededFuture();
        }
    }

    /** Unregistered contract — not in any registry. */
    @DelayedJobContract(name = "ghost-job")
    interface GhostJob extends DelayedJobClient<String> {}

    // --- Helpers ---

    /**
     * Returns a lenient {@link ConfigParser} for use in test call-sites that need to parse config.
     *
     * @return a {@link DefaultConfigParser} with lenient mapper
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    // --- Helper: build resolver with given executors and config ---

    /**
     * Builds a {@link DelayedJobTargetResolver} with the given executors and config, wiring the
     * contributor and registrar in the same way the production module does.
     *
     * @param executors the set of executor instances to register as contributors
     * @param config    the root application config for contract overrides
     * @return the configured resolver
     */
    private static DelayedJobTargetResolver buildResolver(Set<Object> executors, JsonObject config) {
        DelayedJobContractContributor contributor = new DelayedJobContractContributor(executors);
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(), Set.of(contributor), new JsonObject(), configParser());
        DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);
        registrar.scan();
        return new DefaultDelayedJobTargetResolver(
                registry,
                registrar,
                DelayedJobsConfig.fromConfig(config, configParser()).contractIndex());
    }

    // --- Tests ---

    @Nested
    @DisplayName("resolve by contract class")
    class ResolveByContractClass {

        @Test
        @DisplayName("returns correct target id from @DelayedJobContract name")
        void correctTargetId() {
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), new JsonObject());

            ResolvedDelayedJobTarget target = resolver.resolve(AlphaJob.class);

            assertEquals("alpha-job", target.targetId());
        }

        @Test
        @DisplayName("handler name equals target id for typed contracts")
        void handlerNameEqualsTargetId() {
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), new JsonObject());

            ResolvedDelayedJobTarget target = resolver.resolve(AlphaJob.class);

            assertEquals(target.targetId(), target.handlerName());
        }

        @Test
        @DisplayName("handler address follows jobs/delayed/{name}/execute pattern")
        void handlerAddressFollowsPattern() {
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), new JsonObject());

            ResolvedDelayedJobTarget target = resolver.resolve(AlphaJob.class);

            assertEquals("jobs/delayed/alpha-job/execute", target.handlerAddress());
        }

        @Test
        @DisplayName("annotation defaults are used when no config override")
        void annotationDefaultsUsedWithoutConfig() {
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), new JsonObject());

            ResolvedDelayedJobTarget target = resolver.resolve(AlphaJob.class);

            assertEquals(5, target.maxAttempts());
            assertEquals("priority", target.queue());
            assertEquals(10, target.priority());
        }

        @Test
        @DisplayName("framework defaults used when annotation has defaults")
        void frameworkDefaultsWhenAnnotationHasDefaults() {
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new BetaJobExecutor()), new JsonObject());

            ResolvedDelayedJobTarget target = resolver.resolve(BetaJob.class);

            assertEquals(3, target.maxAttempts());
            assertEquals("default", target.queue());
            assertEquals(0, target.priority());
        }

        @Test
        @DisplayName("throws IllegalArgumentException for unregistered contract")
        void throwsForUnknownContract() {
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), new JsonObject());

            assertThrows(IllegalArgumentException.class, () -> resolver.resolve(GhostJob.class));
        }
    }

    @Nested
    @DisplayName("resolve by target id")
    class ResolveByTargetId {

        @Test
        @DisplayName("returns same result as resolve by contract class")
        void sameResultAsContractClassLookup() {
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), new JsonObject());

            ResolvedDelayedJobTarget byClass = resolver.resolve(AlphaJob.class);
            ResolvedDelayedJobTarget byId = resolver.resolve("alpha-job");

            assertEquals(byClass, byId);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for unknown target id")
        void throwsForUnknownTargetId() {
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), new JsonObject());

            assertThrows(IllegalArgumentException.class, () -> resolver.resolve("no-such-job"));
        }
    }

    @Nested
    @DisplayName("config overrides annotation defaults")
    class ConfigOverrides {

        @Test
        @DisplayName("config maxAttempts overrides annotation value")
        void configMaxAttemptsOverridesAnnotation() {
            JsonObject config = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put("alpha-job", new JsonObject().put("maxAttempts", 10))));
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), config);

            ResolvedDelayedJobTarget target = resolver.resolve("alpha-job");

            assertEquals(10, target.maxAttempts());
        }

        @Test
        @DisplayName("config queue overrides annotation value")
        void configQueueOverridesAnnotation() {
            JsonObject config = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject().put("alpha-job", new JsonObject().put("queue", "bulk"))));
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), config);

            ResolvedDelayedJobTarget target = resolver.resolve("alpha-job");

            assertEquals("bulk", target.queue());
        }

        @Test
        @DisplayName("config priority overrides annotation value")
        void configPriorityOverridesAnnotation() {
            JsonObject config = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject().put("alpha-job", new JsonObject().put("priority", 99))));
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), config);

            ResolvedDelayedJobTarget target = resolver.resolve("alpha-job");

            assertEquals(99, target.priority());
        }

        @Test
        @DisplayName("unrelated contract config does not affect target")
        void unrelatedConfigDoesNotAffectTarget() {
            JsonObject config = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put("other-job", new JsonObject().put("maxAttempts", 99))));
            DelayedJobTargetResolver resolver = buildResolver(Set.of(new AlphaJobExecutor()), config);

            ResolvedDelayedJobTarget target = resolver.resolve("alpha-job");

            // Annotation value 5 still applies — config was for a different contract
            assertEquals(5, target.maxAttempts());
        }
    }

    @Nested
    @DisplayName("multiple contracts")
    class MultipleContracts {

        @Test
        @DisplayName("both contracts independently resolvable by class")
        void bothContractsResolvableByClass() {
            DelayedJobTargetResolver resolver =
                    buildResolver(Set.of(new AlphaJobExecutor(), new BetaJobExecutor()), new JsonObject());

            ResolvedDelayedJobTarget alpha = resolver.resolve(AlphaJob.class);
            ResolvedDelayedJobTarget beta = resolver.resolve(BetaJob.class);

            assertEquals("alpha-job", alpha.targetId());
            assertEquals("beta-job", beta.targetId());
        }

        @Test
        @DisplayName("both contracts independently resolvable by target id")
        void bothContractsResolvableById() {
            DelayedJobTargetResolver resolver =
                    buildResolver(Set.of(new AlphaJobExecutor(), new BetaJobExecutor()), new JsonObject());

            ResolvedDelayedJobTarget alpha = resolver.resolve("alpha-job");
            ResolvedDelayedJobTarget beta = resolver.resolve("beta-job");

            assertEquals("alpha-job", alpha.targetId());
            assertEquals("beta-job", beta.targetId());
        }
    }

    // --- Annotation-based handler fixtures ---

    @dev.vertique.services.ServiceContract(value = "handler-svc")
    interface HandlerService {
        @dev.vertique.services.ServiceOperation("process")
        Future<Void> process(String payload);
    }

    static class HandlerServiceImpl implements HandlerService {
        @DelayedJobHandlerMethod("annotation-handler")
        @Override
        public Future<Void> process(String payload) {
            return Future.succeededFuture();
        }
    }

    @Nested
    @DisplayName("annotation-based handlers (@DelayedJobHandlerMethod)")
    class AnnotationBasedHandlers {

        @Test
        @DisplayName("resolve by target id returns framework defaults for annotation-based handler")
        void resolveAnnotationHandlerByTargetId() {
            ServiceContractRegistry registry = ServiceContractRegistry.build(
                    Set.of(new HandlerServiceImpl()), Set.of(), new JsonObject(), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);
            registrar.scan();
            DelayedJobTargetResolver resolver = new DefaultDelayedJobTargetResolver(registry, registrar, Map.of());

            ResolvedDelayedJobTarget target = resolver.resolve("annotation-handler");

            assertEquals("annotation-handler", target.targetId());
            assertEquals("annotation-handler", target.handlerName());
            assertNotNull(target.handlerAddress(), "handler address must be resolved");
            assertEquals("services/handler-svc/process", target.handlerAddress());
            assertEquals("default", target.queue());
            assertEquals(0, target.priority());
            assertEquals(3, target.maxAttempts());
        }

        @Test
        @DisplayName("unknown target id throws IllegalArgumentException")
        void unknownTargetIdThrows() {
            ServiceContractRegistry registry = ServiceContractRegistry.build(
                    Set.of(new HandlerServiceImpl()), Set.of(), new JsonObject(), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);
            registrar.scan();
            DelayedJobTargetResolver resolver = new DefaultDelayedJobTargetResolver(registry, registrar, Map.of());

            assertThrows(IllegalArgumentException.class, () -> resolver.resolve("no-such-handler"));
        }
    }
}
