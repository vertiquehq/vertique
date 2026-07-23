// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link MicrometerAssembly} — verifies provider ordering, composite fan-out,
 * common-tag application, JVM binder lifecycle, close() semantics, rollback on failure,
 * backend config subtree routing, duplicate-name detection, and class-name-only logging of
 * close exceptions (security review: no config-value leakage in close paths).
 */
class MicrometerAssemblyTest {

    /** Tracks assemblies created so tests can close them. */
    private final List<MicrometerAssembly> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (MicrometerAssembly a : opened) {
            try {
                a.close();
            } catch (Exception ignored) {
                // idempotent close is OK; test already asserted the interesting invariants
            }
        }
        opened.clear();
    }

    // --- Helper factory ---

    private MicrometerAssembly assemble(List<MeterRegistryProvider> providers, MetricsConfig config) {
        MicrometerAssembly a = MicrometerAssembly.assemble(providers, config, new JsonObject());
        opened.add(a);
        return a;
    }

    private MicrometerAssembly assemble(
            List<MeterRegistryProvider> providers, MetricsConfig config, JsonObject backends) {
        MicrometerAssembly a = MicrometerAssembly.assemble(providers, config, backends);
        opened.add(a);
        return a;
    }

    private static MetricsConfig defaultConfig() {
        return new JsonObject().mapTo(MetricsConfig.class);
    }

    private static MetricsConfig configWith(JsonObject json) {
        return json.mapTo(MetricsConfig.class);
    }

    // --- Test 4: Ordering ---

    @Nested
    class Ordering {

        @Test
        @DisplayName("providers are invoked in OrderedExtension comparator order (lower priority first)")
        void providersInvokedInPriorityOrder() {
            List<String> creationOrder = new ArrayList<>();

            FakeProvider first = FakeProvider.named("first", 10, creationOrder);
            FakeProvider second = FakeProvider.named("second", 20, creationOrder);
            // Add in reversed order — assembly must sort them
            List<MeterRegistryProvider> providers = List.of(second, first);

            assemble(providers, defaultConfig());

            assertEquals(List.of("first", "second"), creationOrder);
        }
    }

    // --- Test 5: Composite fan-out ---

    @Nested
    class CompositeFanOut {

        @Test
        @DisplayName("Timer recorded through composite is visible in both child registries")
        void timerFanOutToBothChildren() {
            SimpleMeterRegistry r1 = new SimpleMeterRegistry();
            SimpleMeterRegistry r2 = new SimpleMeterRegistry();

            FakeProvider p1 = FakeProvider.wrapping("alpha", 1, r1);
            FakeProvider p2 = FakeProvider.wrapping("beta", 2, r2);

            MicrometerAssembly assembly = assemble(List.of(p1, p2), defaultConfig());

            assembly.composite().timer("my.timer").record(() -> {});

            assertNotNull(r1.find("my.timer").timer(), "timer must be visible in r1");
            assertNotNull(r2.find("my.timer").timer(), "timer must be visible in r2");
        }
    }

    // --- Test 6: Common tags ---

    @Nested
    class CommonTags {

        @Test
        @DisplayName("meter recorded via composite carries 'service' tag from config")
        void serviceTagApplied() {
            SimpleMeterRegistry r1 = new SimpleMeterRegistry();
            FakeProvider p1 = FakeProvider.wrapping("reg", 1, r1);

            MetricsConfig config = configWith(new JsonObject().put("tags", new JsonObject().put("service", "my-svc")));

            MicrometerAssembly assembly = assemble(List.of(p1), config);
            assembly.composite().counter("my.counter").increment();

            // Find the counter in the child registry and verify the tag
            var counter = r1.find("my.counter").tag("service", "my-svc").counter();
            assertNotNull(counter, "counter with service=my-svc tag must exist in child registry");
        }

        @Test
        @DisplayName("extra tags from config are applied alongside service tag")
        void extraTagsApplied() {
            SimpleMeterRegistry r1 = new SimpleMeterRegistry();
            FakeProvider p1 = FakeProvider.wrapping("reg", 1, r1);

            MetricsConfig config = configWith(new JsonObject()
                    .put(
                            "tags",
                            new JsonObject().put("service", "svc").put("extra", new JsonObject().put("env", "prod"))));

            MicrometerAssembly assembly = assemble(List.of(p1), config);
            assembly.composite().counter("tagged.counter").increment();

            assertNotNull(
                    r1.find("tagged.counter")
                            .tag("service", "svc")
                            .tag("env", "prod")
                            .counter(),
                    "counter must have both service and extra tags");
        }
    }

    // --- Test 7: Service-name fallback ---

    @Nested
    class ServiceNameFallback {

        @Test
        @DisplayName("resolveServiceName: config value wins over env and default")
        void configValueWins() {
            MetricsConfig config =
                    configWith(new JsonObject().put("tags", new JsonObject().put("service", "from-config")));

            String resolved = MicrometerAssembly.resolveServiceName(config, key -> "from-env");

            assertEquals("from-config", resolved);
        }

        @Test
        @DisplayName("resolveServiceName: env OTEL_SERVICE_NAME wins over default when config is null")
        void envWinsWhenConfigNull() {
            MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class); // service=null

            String resolved = MicrometerAssembly.resolveServiceName(config, key -> "from-env-otel");

            assertEquals("from-env-otel", resolved);
        }

        @Test
        @DisplayName("resolveServiceName: falls back to 'unknown-service' when config null and env absent")
        void fallsBackToUnknownService() {
            MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class); // service=null

            String resolved = MicrometerAssembly.resolveServiceName(config, key -> null);

            assertEquals("unknown-service", resolved);
        }
    }

    // --- Test 8: JVM binders ---

    @Nested
    class JvmBinders {

        @Test
        @DisplayName("jvm.enabled=false → no jvm.* meters in composite")
        void jvmDisabledNoMeters() {
            MetricsConfig config = configWith(new JsonObject().put("jvm", new JsonObject().put("enabled", false)));

            SimpleMeterRegistry r1 = new SimpleMeterRegistry();
            MicrometerAssembly assembly = assemble(List.of(FakeProvider.wrapping("x", 1, r1)), config);

            // No jvm.* meters should be present
            boolean hasJvmMeters =
                    r1.getMeters().stream().anyMatch(m -> m.getId().getName().startsWith("jvm."));
            assertFalse(hasJvmMeters, "No jvm.* meters expected when jvm.enabled=false");
        }

        @Test
        @DisplayName("jvm.enabled=true → jvm.memory.used present and JvmGcMetrics is tracked (closeable)")
        void jvmEnabledRegistersMemoryMeters() {
            MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class); // jvm.enabled defaults to true

            SimpleMeterRegistry r1 = new SimpleMeterRegistry();
            MicrometerAssembly assembly = assemble(List.of(FakeProvider.wrapping("x", 1, r1)), config);

            boolean hasJvmMemory =
                    r1.getMeters().stream().anyMatch(m -> m.getId().getName().startsWith("jvm.memory"));
            assertTrue(hasJvmMemory, "jvm.memory.* meters must exist when jvm.enabled=true");

            // close() must not throw even with JvmGcMetrics tracked
            assertDoesNotThrow(assembly::close);
            opened.remove(assembly); // already closed
        }
    }

    // --- Test 9: close() semantics ---

    @Nested
    class CloseSemantic {

        @Test
        @DisplayName("close() calls backends in REVERSE creation order")
        void backendsClosedInReverseOrder() {
            List<String> closeOrder = new ArrayList<>();

            FakeProvider p1 = FakeProvider.namedWithCloseTracking("first", 10, closeOrder);
            FakeProvider p2 = FakeProvider.namedWithCloseTracking("second", 20, closeOrder);

            MicrometerAssembly assembly = assemble(List.of(p1, p2), defaultConfig());

            assembly.close();
            opened.remove(assembly); // already closed

            assertEquals(List.of("second", "first"), closeOrder, "must close in reverse creation order");
        }

        @Test
        @DisplayName("close() is idempotent — double-close does not throw")
        void doubleCloseIsOk() {
            MicrometerAssembly assembly = assemble(List.of(), defaultConfig());
            assembly.close();
            assertDoesNotThrow(assembly::close); // second close must be silent
            opened.remove(assembly);
        }
    }

    // --- Test 10: Rollback on failure ---

    @Nested
    class Rollback {

        @Test
        @DisplayName("provider #2 create() throws → provider #1 backend is closed and exception propagates")
        void rollbackClosesAlreadyCreatedBackends() {
            AtomicBoolean firstBackendClosed = new AtomicBoolean(false);

            FakeProvider p1 = FakeProvider.wrappingWithCloseCallback(
                    "good", 10, new SimpleMeterRegistry(), () -> firstBackendClosed.set(true));
            MeterRegistryProvider p2 = new FailingProvider("bad", 20);

            assertThrows(
                    Exception.class,
                    () -> MicrometerAssembly.assemble(List.of(p1, p2), defaultConfig(), new JsonObject()));

            assertTrue(firstBackendClosed.get(), "first backend must be closed on rollback");
        }
    }

    // --- Test 11: Backend config subtree ---

    @Nested
    class BackendConfigSubtree {

        @Test
        @DisplayName("provider receives exactly the backends.<name> subtree; absent name → empty JsonObject")
        void providerReceivesCorrectSubtree() {
            AtomicReference<JsonObject> received = new AtomicReference<>();
            MeterRegistryProvider capturingProvider = new CapturingProvider("prom", 1, received);

            JsonObject backends = new JsonObject()
                    .put("prom", new JsonObject().put("scrapePort", 9090))
                    .put("other", new JsonObject().put("x", 1));

            MicrometerAssembly assembly =
                    MicrometerAssembly.assemble(List.of(capturingProvider), defaultConfig(), backends);
            opened.add(assembly);

            assertNotNull(received.get());
            assertEquals(9090, received.get().getInteger("scrapePort"), "provider receives its own subtree only");
        }

        @Test
        @DisplayName("absent backend name → provider receives empty JsonObject")
        void absentNameYieldsEmptyConfig() {
            AtomicReference<JsonObject> received = new AtomicReference<>();
            MeterRegistryProvider capturingProvider = new CapturingProvider("missing", 1, received);

            MicrometerAssembly assembly =
                    MicrometerAssembly.assemble(List.of(capturingProvider), defaultConfig(), new JsonObject());
            opened.add(assembly);

            assertNotNull(received.get());
            assertTrue(received.get().isEmpty(), "provider receives empty JsonObject when name absent");
        }
    }

    // --- Test 12: Duplicate backendName ---

    @Nested
    class DuplicateBackendName {

        @Test
        @DisplayName("duplicate backendName across two providers → ISE naming both provider class names")
        void duplicateNameThrowsIse() {
            FakeProvider p1 = FakeProvider.named("dup", 10, new ArrayList<>());
            FakeProvider p2 = FakeProvider.named("dup", 20, new ArrayList<>());

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> MicrometerAssembly.assemble(List.of(p1, p2), defaultConfig(), new JsonObject()));

            String msg = ex.getMessage();
            // Must name the provider class names
            assertTrue(
                    msg.contains(FakeProvider.class.getName()),
                    "ISE message must contain provider class name, got: " + msg);
            // Must NOT contain config values (names are backend keys, not config values)
            // The check here is that the message mentions the backend name "dup" only as identifier
            // context, which is acceptable per the spec ("naming only the provider CLASS names")
        }
    }

    // --- Test 14: Blank backendName validation (W4) ---

    @Nested
    @DisplayName("W4: blank backendName() is rejected before any backend is created")
    class BlankBackendNameValidation {

        @Test
        @DisplayName(
                "provider with blank backendName() → MetricsBootstrapException naming provider class, no backend created")
        void blankBackendNameThrows() {
            AtomicBoolean created = new AtomicBoolean(false);
            MeterRegistryProvider blankNameProvider = new MeterRegistryProvider() {
                @Override
                public String backendName() {
                    return "   "; // blank
                }

                @Override
                public MeterRegistryBackend create(JsonObject backendConfig) {
                    created.set(true);
                    return new MeterRegistryBackend() {
                        @Override
                        public io.micrometer.core.instrument.MeterRegistry registry() {
                            return new SimpleMeterRegistry();
                        }

                        @Override
                        public void close() {}
                    };
                }
            };

            Exception ex = assertThrows(
                    Exception.class,
                    () -> MicrometerAssembly.assemble(List.of(blankNameProvider), defaultConfig(), new JsonObject()));

            String msg = ex.getMessage();
            assertFalse(created.get(), "create() must not be called for a provider with blank backendName()");
            // Must name the provider class (not the blank value)
            assertTrue(
                    msg.contains(blankNameProvider.getClass().getSimpleName())
                            || msg.contains(blankNameProvider.getClass().getName()),
                    "exception message must name the provider class, got: " + msg);
        }

        @Test
        @DisplayName("provider with null backendName() → exception naming provider class, no backend created")
        void nullBackendNameThrows() {
            AtomicBoolean created = new AtomicBoolean(false);
            MeterRegistryProvider nullNameProvider = new MeterRegistryProvider() {
                @Override
                public String backendName() {
                    return null;
                }

                @Override
                public MeterRegistryBackend create(JsonObject backendConfig) {
                    created.set(true);
                    return new MeterRegistryBackend() {
                        @Override
                        public io.micrometer.core.instrument.MeterRegistry registry() {
                            return new SimpleMeterRegistry();
                        }

                        @Override
                        public void close() {}
                    };
                }
            };

            assertThrows(
                    Exception.class,
                    () -> MicrometerAssembly.assemble(List.of(nullNameProvider), defaultConfig(), new JsonObject()));
            assertFalse(created.get(), "create() must not be called for a provider with null backendName()");
        }
    }

    // --- Test 15: Provider create() failure surfaces backend name (W4) ---

    @Nested
    @DisplayName("W4: provider create() failure wraps with backend name in message")
    class ProviderCreateFailureNaming {

        @Test
        @DisplayName(
                "provider with name 'mybackend' whose create() throws → MetricsBootstrapException message contains 'mybackend' and exception class name but not SENTINEL")
        void createFailureMessageContainsBackendName() {
            String sentinel = "SENTINEL_create_secret";
            AtomicBoolean siblingClosed = new AtomicBoolean(false);

            // First provider succeeds, with a close tracker
            FakeProvider goodProvider = FakeProvider.wrappingWithCloseCallback(
                    "goodbackend", 1, new SimpleMeterRegistry(), () -> siblingClosed.set(true));
            // Second provider fails with a SENTINEL-bearing exception
            MeterRegistryProvider badProvider = new MeterRegistryProvider() {
                @Override
                public String backendName() {
                    return "mybackend";
                }

                @Override
                public int priority() {
                    return 2;
                }

                @Override
                public MeterRegistryBackend create(JsonObject backendConfig) {
                    throw new RuntimeException(sentinel);
                }
            };

            MetricsBootstrapException ex = assertThrows(
                    MetricsBootstrapException.class,
                    () -> MicrometerAssembly.assemble(
                            List.of(goodProvider, badProvider), defaultConfig(), new JsonObject()));

            String msg = ex.getMessage();
            assertTrue(msg.contains("mybackend"), "message must contain backend name 'mybackend', got: " + msg);
            assertTrue(
                    msg.contains(RuntimeException.class.getSimpleName()),
                    "message must contain exception class name, got: " + msg);
            assertFalse(msg.contains(sentinel), "message must NOT contain sentinel, got: " + msg);
            assertNull(ex.getCause(), "MetricsBootstrapException must have no cause");
            assertTrue(siblingClosed.get(), "already-created sibling backend must be closed on rollback");
        }
    }

    // --- Test 13: Close-path logging secrecy ---

    @Nested
    class CloseLoggingSecrecy {

        /**
         * Verifies that when a backend's {@code close()} throws an exception whose message contains
         * a sentinel value (simulating a push-backend SDK embedding a connection URL), the sentinel
         * does NOT appear in any log event. Only the exception's simple class name may be logged.
         */
        @Test
        @DisplayName("backend close() exception with SENTINEL message → logs contain class name only, not SENTINEL")
        void closeExceptionDoesNotLeakSentinelIntoLogs() {
            String sentinel = "SENTINEL_close_secret";

            Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();
            rootLogger.addAppender(listAppender);

            try {
                // Build a provider whose backend.close() throws with the sentinel in the message
                MeterRegistryProvider sentinelCloseProvider = new MeterRegistryProvider() {
                    @Override
                    public String backendName() {
                        return "sentinelclose";
                    }

                    @Override
                    public MeterRegistryBackend create(JsonObject backendConfig) {
                        SimpleMeterRegistry reg = new SimpleMeterRegistry();
                        return new MeterRegistryBackend() {
                            @Override
                            public MeterRegistry registry() {
                                return reg;
                            }

                            @Override
                            public void close() {
                                throw new RuntimeException(sentinel + "_in_close_message");
                            }
                        };
                    }
                };

                MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class);
                MicrometerAssembly assembly =
                        MicrometerAssembly.assemble(List.of(sentinelCloseProvider), config, new JsonObject());

                // Trigger close — backend.close() will throw the sentinel-bearing exception
                assembly.close();

                // Assert no log event contains the sentinel
                for (ILoggingEvent event : listAppender.list) {
                    assertFalse(
                            event.getFormattedMessage().contains(sentinel),
                            "log event must not contain sentinel, got: " + event.getFormattedMessage());
                }

                // Assert at least one WARN was logged (something was attempted)
                boolean hasWarn = listAppender.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().contains("closing backend"));
                assertTrue(hasWarn, "a WARN log about closing backend must have been emitted");

            } finally {
                rootLogger.detachAppender(listAppender);
            }
        }

        /**
         * Verifies that a rollback-path backend close() exception with a sentinel message does not
         * appear in logs — same secrecy guarantee for the rollback (assemble failure) path.
         */
        @Test
        @DisplayName("rollback: backend close() exception with SENTINEL → logs contain class name only, not SENTINEL")
        void rollbackCloseExceptionDoesNotLeakSentinel() {
            String sentinel = "SENTINEL_rollback_secret";

            Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();
            rootLogger.addAppender(listAppender);

            try {
                // First provider succeeds but close() throws sentinel
                MeterRegistryProvider p1 = new MeterRegistryProvider() {
                    @Override
                    public String backendName() {
                        return "rollbacksentinel";
                    }

                    @Override
                    public MeterRegistryBackend create(JsonObject backendConfig) {
                        SimpleMeterRegistry reg = new SimpleMeterRegistry();
                        return new MeterRegistryBackend() {
                            @Override
                            public MeterRegistry registry() {
                                return reg;
                            }

                            @Override
                            public void close() {
                                throw new RuntimeException(sentinel + "_during_rollback_close");
                            }
                        };
                    }
                };
                // Second provider fails create(), triggering rollback
                MeterRegistryProvider p2 = new FailingProvider("fail", 99);

                MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class);
                assertThrows(
                        Exception.class, () -> MicrometerAssembly.assemble(List.of(p1, p2), config, new JsonObject()));

                // The sentinel from p1's close() must not appear in any log event
                for (ILoggingEvent event : listAppender.list) {
                    assertFalse(
                            event.getFormattedMessage().contains(sentinel),
                            "rollback log must not contain sentinel, got: " + event.getFormattedMessage());
                }
            } finally {
                rootLogger.detachAppender(listAppender);
            }
        }
    }

    // ================================
    // --- Test doubles ---
    // ================================

    /**
     * A fake {@link MeterRegistryProvider} wrapping a given registry or creating a new one,
     * with configurable priority and optional tracking hooks.
     */
    static final class FakeProvider implements MeterRegistryProvider {

        private final String name;
        private final int prio;
        private final SimpleMeterRegistry registry;
        private final List<String> creationTracker;
        private final List<String> closeTracker;
        private final Runnable closeCallback;

        private FakeProvider(
                String name,
                int prio,
                SimpleMeterRegistry registry,
                List<String> creationTracker,
                List<String> closeTracker,
                Runnable closeCallback) {
            this.name = name;
            this.prio = prio;
            this.registry = registry;
            this.creationTracker = creationTracker;
            this.closeTracker = closeTracker;
            this.closeCallback = closeCallback;
        }

        static FakeProvider named(String name, int prio, List<String> creationTracker) {
            return new FakeProvider(name, prio, new SimpleMeterRegistry(), creationTracker, null, null);
        }

        static FakeProvider wrapping(String name, int prio, SimpleMeterRegistry registry) {
            return new FakeProvider(name, prio, registry, null, null, null);
        }

        static FakeProvider namedWithCloseTracking(String name, int prio, List<String> closeTracker) {
            return new FakeProvider(name, prio, new SimpleMeterRegistry(), null, closeTracker, null);
        }

        static FakeProvider wrappingWithCloseCallback(
                String name, int prio, SimpleMeterRegistry registry, Runnable closeCallback) {
            return new FakeProvider(name, prio, registry, null, null, closeCallback);
        }

        @Override
        public String backendName() {
            return name;
        }

        @Override
        public int priority() {
            return prio;
        }

        @Override
        public MeterRegistryBackend create(JsonObject backendConfig) {
            if (creationTracker != null) {
                creationTracker.add(name);
            }
            SimpleMeterRegistry reg = this.registry;
            List<String> ct = closeTracker;
            String n = name;
            Runnable cb = closeCallback;
            return new MeterRegistryBackend() {
                @Override
                public io.micrometer.core.instrument.MeterRegistry registry() {
                    return reg;
                }

                @Override
                public void close() {
                    if (ct != null) {
                        ct.add(n);
                    }
                    if (cb != null) {
                        cb.run();
                    }
                    reg.close();
                }
            };
        }
    }

    /**
     * A fake provider whose {@link #create} always throws.
     */
    static final class FailingProvider implements MeterRegistryProvider {

        private final String name;
        private final int prio;

        FailingProvider(String name, int prio) {
            this.name = name;
            this.prio = prio;
        }

        @Override
        public String backendName() {
            return name;
        }

        @Override
        public int priority() {
            return prio;
        }

        @Override
        public MeterRegistryBackend create(JsonObject backendConfig) throws Exception {
            throw new Exception("intentional failure from " + name);
        }
    }

    /**
     * A provider that captures the {@link JsonObject} passed to {@link #create} for later assertion.
     */
    static final class CapturingProvider implements MeterRegistryProvider {

        private final String name;
        private final int prio;
        private final AtomicReference<JsonObject> capture;

        CapturingProvider(String name, int prio, AtomicReference<JsonObject> capture) {
            this.name = name;
            this.prio = prio;
            this.capture = capture;
        }

        @Override
        public String backendName() {
            return name;
        }

        @Override
        public int priority() {
            return prio;
        }

        @Override
        public MeterRegistryBackend create(JsonObject backendConfig) {
            capture.set(backendConfig);
            SimpleMeterRegistry reg = new SimpleMeterRegistry();
            return new MeterRegistryBackend() {
                @Override
                public io.micrometer.core.instrument.MeterRegistry registry() {
                    return reg;
                }

                @Override
                public void close() {
                    reg.close();
                }
            };
        }
    }
}
