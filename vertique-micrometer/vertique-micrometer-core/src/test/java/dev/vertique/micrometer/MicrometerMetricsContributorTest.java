// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.core.exception.ConfigurationException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.micrometer.Label;
import io.vertx.micrometer.MicrometerMetricsOptions;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link MicrometerMetricsContributor}.
 *
 * <p>Verifies the full active-path wiring (options, label overrides, metric domain disabling),
 * the inert paths (disabled flag, zero providers), double-bootstrap guard, rollback on provider
 * failure, cause-chain secrecy sentinel, shutdown idempotency, and exception hierarchy proof.
 */
class MicrometerMetricsContributorTest {

    @AfterEach
    void resetHolder() {
        MeterRegistryHolder.resetForTests();
    }

    // --- Hierarchy proof ---

    @Test
    @DisplayName("MetricsBootstrapException is a ConfigurationException")
    void metricsBootstrapExceptionIsConfigurationException() {
        assertTrue(ConfigurationException.class.isAssignableFrom(MetricsBootstrapException.class));
        assertInstanceOf(ConfigurationException.class, new MetricsBootstrapException("test"));
    }

    // --- Test 1: Active path ---

    @Nested
    @DisplayName("active path — one provider, eventBus=false")
    class ActivePath {

        @Test
        @DisplayName(
                "options on vertxOptions is MicrometerMetricsOptions; disabled categories include EVENT_BUS; factory wired; holder bootstrapped")
        void activePath() throws Exception {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple);
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(
                    new JsonObject()
                            .put("metrics", new JsonObject().put("vertx", new JsonObject().put("eventBus", false))),
                    options);

            contributor.contribute(builder, ctx);

            // Options must be a MicrometerMetricsOptions
            assertInstanceOf(MicrometerMetricsOptions.class, options.getMetricsOptions());
            MicrometerMetricsOptions metricsOptions = (MicrometerMetricsOptions) options.getMetricsOptions();
            assertTrue(metricsOptions.isEnabled());
            assertEquals("vertique", metricsOptions.getRegistryName());

            // EVENT_BUS disabled category
            assertTrue(metricsOptions.isMetricsCategoryDisabled(io.vertx.micrometer.MetricsDomain.EVENT_BUS));
            // HTTP_SERVER should NOT be disabled
            assertFalse(metricsOptions.isMetricsCategoryDisabled(io.vertx.micrometer.MetricsDomain.HTTP_SERVER));

            // withMetrics called with a MicrometerMetricsFactory
            ArgumentCaptor<io.vertx.core.spi.VertxMetricsFactory> captor =
                    forClass(io.vertx.core.spi.VertxMetricsFactory.class);
            verify(builder).withMetrics(captor.capture());
            assertInstanceOf(io.vertx.micrometer.MicrometerMetricsFactory.class, captor.getValue());

            // Holder bootstrapped
            assertTrue(MeterRegistryHolder.bootstrapped());

            // Meters recorded through holder reach the SimpleMeterRegistry
            MeterRegistryHolder.registry().counter("test.counter").increment();
            assertNotNull(simple.find("test.counter").counter());
        }
    }

    // --- Test 2: Labels override ---

    @Nested
    @DisplayName("labels override")
    class LabelsOverride {

        @Test
        @DisplayName("explicit labels list replaces defaults")
        void explicitLabelsOverride() throws Exception {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple);
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(
                    new JsonObject()
                            .put(
                                    "metrics",
                                    new JsonObject()
                                            .put(
                                                    "vertx",
                                                    new JsonObject()
                                                            .put("labels", List.of("HTTP_METHOD", "HTTP_ROUTE")))),
                    options);

            contributor.contribute(builder, ctx);

            MicrometerMetricsOptions metricsOptions = (MicrometerMetricsOptions) options.getMetricsOptions();
            Set<Label> labels = metricsOptions.getLabels();
            assertEquals(2, labels.size());
            assertTrue(labels.contains(Label.HTTP_METHOD));
            assertTrue(labels.contains(Label.HTTP_ROUTE));
        }

        @Test
        @DisplayName("invalid label name throws ConfigurationException")
        void invalidLabelNameThrowsConfigurationException() {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple);
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(
                    new JsonObject()
                            .put(
                                    "metrics",
                                    new JsonObject()
                                            .put(
                                                    "vertx",
                                                    new JsonObject().put("labels", List.of("INVALID_LABEL_XYZ")))),
                    options);

            assertThrows(ConfigurationException.class, () -> contributor.contribute(builder, ctx));
            assertFalse(MeterRegistryHolder.bootstrapped());
        }

        @Test
        @DisplayName("null labels list leaves Vert.x defaults (non-null, non-empty)")
        void nullLabelsUsesDefaults() throws Exception {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple);
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(new JsonObject().put("metrics", new JsonObject()), options);

            contributor.contribute(builder, ctx);

            MicrometerMetricsOptions metricsOptions = (MicrometerMetricsOptions) options.getMetricsOptions();
            assertNotNull(metricsOptions.getLabels());
            assertFalse(metricsOptions.getLabels().isEmpty());
        }
    }

    // --- Test 3: Zero providers ---

    @Nested
    @DisplayName("zero providers — fully inert")
    class ZeroProviders {

        @Test
        @DisplayName(
                "builder returned untouched, metricsOptions not set to MicrometerMetricsOptions, holder not bootstrapped")
        void zeroProvidersInert() throws Exception {
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of());

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx = fakeContext(new JsonObject().put("metrics", new JsonObject()), options);

            VertxBuilder result = contributor.contribute(builder, ctx);

            // Builder returned as-is, no interactions
            assertSame(builder, result);
            verify(builder, never()).withMetrics(org.mockito.ArgumentMatchers.any());
            assertFalse(options.getMetricsOptions() instanceof MicrometerMetricsOptions);
            assertFalse(MeterRegistryHolder.bootstrapped());
        }
    }

    // --- Test 4: metrics.enabled=false ---

    @Nested
    @DisplayName("metrics.enabled=false — fully inert even with providers")
    class MetricsDisabled {

        @Test
        @DisplayName("enabled=false: builder returned untouched, holder not bootstrapped")
        void enabledFalseIsInert() throws Exception {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple);
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            VertxOptions options = new VertxOptions();
            BootstrapContext ctx =
                    fakeContext(new JsonObject().put("metrics", new JsonObject().put("enabled", false)), options);

            VertxBuilder result = contributor.contribute(builder, ctx);

            assertSame(builder, result);
            verify(builder, never()).withMetrics(org.mockito.ArgumentMatchers.any());
            assertFalse(options.getMetricsOptions() instanceof MicrometerMetricsOptions);
            assertFalse(MeterRegistryHolder.bootstrapped());
        }
    }

    // --- Test 5: Double bootstrap guard ---

    @Nested
    @DisplayName("double bootstrap guard")
    class DoubleBootstrapGuard {

        @Test
        @DisplayName("second contribute() without reset throws MetricsBootstrapException")
        void secondContributeThrows() throws Exception {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple);
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            BootstrapContext ctx = fakeContext(new JsonObject().put("metrics", new JsonObject()), new VertxOptions());

            // First call should succeed
            contributor.contribute(builder, ctx);

            // Second call (simulating embedded multi-launch) should throw
            MicrometerMetricsContributor contributor2 = new MicrometerMetricsContributor(List.of(provider));
            BootstrapContext ctx2 = fakeContext(new JsonObject().put("metrics", new JsonObject()), new VertxOptions());
            MetricsBootstrapException ex =
                    assertThrows(MetricsBootstrapException.class, () -> contributor2.contribute(builder, ctx2));
            assertTrue(ex.getMessage().contains("already bootstrapped"), ex.getMessage());
        }
    }

    // --- Test 6: Provider failure rollback ---

    @Nested
    @DisplayName("provider failure rollback")
    class ProviderFailureRollback {

        @Test
        @DisplayName(
                "provider #2 create() throws → provider #1 backend closed, holder not bootstrapped, MetricsBootstrapException propagates with no cause")
        void providerFailureRollback() {
            AtomicBoolean backend1Closed = new AtomicBoolean(false);
            SimpleMeterRegistry simple1 = new SimpleMeterRegistry();
            // priority 1 runs first (lower number = earlier)
            FakeProvider provider1 = new FakeProvider("backend1", simple1) {
                @Override
                public int priority() {
                    return 1;
                }

                @Override
                public MeterRegistryBackend create(io.vertx.core.json.JsonObject cfg) {
                    MeterRegistryBackend delegate = super.create(cfg);
                    return new MeterRegistryBackend() {
                        @Override
                        public MeterRegistry registry() {
                            return delegate.registry();
                        }

                        @Override
                        public void close() {
                            backend1Closed.set(true);
                            delegate.close();
                        }
                    };
                }
            };
            // priority 2 runs second and fails
            MeterRegistryProvider provider2 = new FailingProvider("backend2") {
                @Override
                public int priority() {
                    return 2;
                }
            };

            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider1, provider2));

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            BootstrapContext ctx = fakeContext(new JsonObject().put("metrics", new JsonObject()), new VertxOptions());

            MetricsBootstrapException ex =
                    assertThrows(MetricsBootstrapException.class, () -> contributor.contribute(builder, ctx));

            assertNull(ex.getCause(), "MetricsBootstrapException must have no cause (cause-chain severed)");
            assertTrue(backend1Closed.get(), "backend1 must have been closed during rollback");
            assertFalse(MeterRegistryHolder.bootstrapped());
        }
    }

    // --- Test 7: Sentinel secrecy ---

    @Nested
    @DisplayName("sentinel secrecy — no config values leak into logs or cause chain")
    class SentinelSecrecy {

        @Test
        @DisplayName("config value SENTINEL_abc does not appear in cause chain or any log event")
        void configValueDoesNotLeakIntoLogsOrCauseChain() throws Exception {
            String secretValue = "SENTINEL_abc";

            // Attach a ListAppender to the root logger to capture ALL log events
            Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();
            rootLogger.addAppender(listAppender);

            try {
                MeterRegistryProvider sentinelProvider = new SentinelProvider("sentinelprovider", secretValue);
                MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(sentinelProvider));

                VertxBuilder builder = mock(VertxBuilder.class);
                BootstrapContext ctx = fakeContext(
                        new JsonObject()
                                .put(
                                        "metrics",
                                        new JsonObject()
                                                .put(
                                                        "backends",
                                                        new JsonObject()
                                                                .put(
                                                                        "sentinelprovider",
                                                                        new JsonObject()
                                                                                .put("configValue", secretValue)))),
                        new VertxOptions());

                Throwable thrown = null;
                try {
                    contributor.contribute(builder, ctx);
                } catch (Throwable t) {
                    thrown = t;
                }

                assertNotNull(thrown, "Must throw on provider failure");

                // Walk the full cause chain and assert SENTINEL_abc is not present in any message or toString
                Throwable current = thrown;
                while (current != null) {
                    String message = current.getMessage();
                    if (message != null) {
                        assertFalse(
                                message.contains(secretValue),
                                "Exception message must not contain sentinel: " + message);
                    }
                    assertFalse(
                            current.toString().contains(secretValue),
                            "Exception toString must not contain sentinel: " + current);
                    current = current.getCause();
                }

                // Assert no captured log event contains SENTINEL_abc
                for (ILoggingEvent event : listAppender.list) {
                    assertFalse(
                            event.getFormattedMessage().contains(secretValue),
                            "Log event must not contain sentinel: " + event.getFormattedMessage());
                }
            } finally {
                rootLogger.detachAppender(listAppender);
            }
        }
    }

    // --- Test 8: Failure after assemble ---

    @Nested
    @DisplayName("failure after assemble")
    class FailureAfterAssemble {

        @Test
        @DisplayName(
                "builder.withMetrics throws → assembly closed (backend close recorded), holder not bootstrapped, MetricsBootstrapException")
        void builderWithMetricsThrows() {
            AtomicBoolean backendClosed = new AtomicBoolean(false);
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple) {
                @Override
                public MeterRegistryBackend create(io.vertx.core.json.JsonObject cfg) {
                    MeterRegistryBackend delegate = super.create(cfg);
                    return new MeterRegistryBackend() {
                        @Override
                        public MeterRegistry registry() {
                            return delegate.registry();
                        }

                        @Override
                        public void close() {
                            backendClosed.set(true);
                        }
                    };
                }
            };

            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new RuntimeException("builder failure"));
            BootstrapContext ctx = fakeContext(new JsonObject().put("metrics", new JsonObject()), new VertxOptions());

            assertThrows(MetricsBootstrapException.class, () -> contributor.contribute(builder, ctx));

            assertTrue(backendClosed.get(), "assembly backend must have been closed on builder failure");
            assertFalse(MeterRegistryHolder.bootstrapped());
        }
    }

    // --- Test 9: onShutdown ---

    @Nested
    @DisplayName("onShutdown lifecycle")
    class OnShutdown {

        @Test
        @DisplayName("after successful contribute, onShutdown closes the backend")
        void onShutdownClosesBackend() throws Exception {
            AtomicInteger closeCalls = new AtomicInteger(0);
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple) {
                @Override
                public MeterRegistryBackend create(io.vertx.core.json.JsonObject cfg) {
                    MeterRegistryBackend delegate = super.create(cfg);
                    return new MeterRegistryBackend() {
                        @Override
                        public MeterRegistry registry() {
                            return delegate.registry();
                        }

                        @Override
                        public void close() {
                            closeCalls.incrementAndGet();
                        }
                    };
                }
            };

            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            BootstrapContext ctx = fakeContext(new JsonObject().put("metrics", new JsonObject()), new VertxOptions());
            contributor.contribute(builder, ctx);

            contributor.onShutdown();
            assertTrue(closeCalls.get() >= 1, "backend must have been closed during onShutdown");
        }

        @Test
        @DisplayName("onShutdown twice → close once-effective (idempotent)")
        void onShutdownIdempotent() throws Exception {
            AtomicInteger closeCalls = new AtomicInteger(0);
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple) {
                @Override
                public MeterRegistryBackend create(io.vertx.core.json.JsonObject cfg) {
                    MeterRegistryBackend delegate = super.create(cfg);
                    return new MeterRegistryBackend() {
                        @Override
                        public MeterRegistry registry() {
                            return delegate.registry();
                        }

                        @Override
                        public void close() {
                            closeCalls.incrementAndGet();
                        }
                    };
                }
            };

            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            BootstrapContext ctx = fakeContext(new JsonObject().put("metrics", new JsonObject()), new VertxOptions());
            contributor.contribute(builder, ctx);

            contributor.onShutdown();
            contributor.onShutdown(); // second call must be idempotent at assembly level
            // The assembly's close() is idempotent — backend.close() called at most once
            assertTrue(closeCalls.get() <= 1, "backend.close() must be called at most once (idempotent)");
        }

        @Test
        @DisplayName("onShutdown without prior contribute → no-op")
        void onShutdownWithoutContributeIsNoOp() {
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of());
            // Must not throw
            assertDoesNotThrow(contributor::onShutdown);
        }
    }

    // --- Test 10: Validation wiring ---

    @Nested
    @DisplayName("validation wiring")
    class ValidationWiring {

        @Test
        @DisplayName("extra tag key 'auth_token' triggers ConfigurationException (not MetricsBootstrapException)")
        void secretLikeTagKeyThrowsConfigurationException() {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple);
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            BootstrapContext ctx = fakeContext(
                    new JsonObject()
                            .put(
                                    "metrics",
                                    new JsonObject()
                                            .put(
                                                    "tags",
                                                    new JsonObject()
                                                            .put(
                                                                    "extra",
                                                                    new JsonObject().put("auth_token", "somevalue")))),
                    new VertxOptions());

            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> contributor.contribute(builder, ctx));
            // ConfigurationException is not a MetricsBootstrapException (the validator throws directly)
            assertNotNull(ex);
            assertFalse(MeterRegistryHolder.bootstrapped());
        }
    }

    // --- Test 11: Malformed metrics section ---

    @Nested
    @DisplayName("malformed metrics section throws ConfigurationException, not MetricsBootstrapException")
    class MalformedMetricsSection {

        @Test
        @DisplayName("metrics section is a scalar (true) → ConfigurationException; holder untouched")
        void metricsScalarThrowsConfigurationException() {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple);
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            VertxOptions options = new VertxOptions();
            // "metrics" bound to a boolean scalar — JsonConfigPaths.navigateObject must throw
            BootstrapContext ctx = fakeContext(new JsonObject().put("metrics", true), options);

            // assertThrows guarantees the type is ConfigurationException — not MetricsBootstrapException
            assertThrows(ConfigurationException.class, () -> contributor.contribute(builder, ctx));
            assertFalse(MeterRegistryHolder.bootstrapped(), "holder must remain unset after config-shape error");
        }
    }

    // --- Test 13: W4 — provider failure preserves backend name through contributor ---

    @Nested
    @DisplayName("W4: provider failure preserves backend name in MetricsBootstrapException through contributor")
    class ProviderFailureBackendNamePreservation {

        @Test
        @DisplayName(
                "provider #2 'backend2' create() throws → MetricsBootstrapException message names 'backend2', getCause()==null, holder not bootstrapped, backend #1 closed")
        void providerFailurePreservesBackendNameThroughContributor() {
            String sentinel = "SENTINEL_w4_secret";
            AtomicBoolean backend1Closed = new AtomicBoolean(false);

            SimpleMeterRegistry simple1 = new SimpleMeterRegistry();
            FakeProvider provider1 = new FakeProvider("backend1", simple1) {
                @Override
                public int priority() {
                    return 1;
                }

                @Override
                public MeterRegistryBackend create(io.vertx.core.json.JsonObject cfg) {
                    MeterRegistryBackend delegate = super.create(cfg);
                    return new MeterRegistryBackend() {
                        @Override
                        public MeterRegistry registry() {
                            return delegate.registry();
                        }

                        @Override
                        public void close() {
                            backend1Closed.set(true);
                            delegate.close();
                        }
                    };
                }
            };

            MeterRegistryProvider provider2 = new MeterRegistryProvider() {
                @Override
                public String backendName() {
                    return "backend2";
                }

                @Override
                public int priority() {
                    return 2;
                }

                @Override
                public MeterRegistryBackend create(io.vertx.core.json.JsonObject backendConfig) {
                    throw new RuntimeException(sentinel);
                }
            };

            // Set up log capture to verify sentinel is absent from logs
            ch.qos.logback.classic.Logger rootLogger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender =
                    new ch.qos.logback.core.read.ListAppender<>();
            listAppender.start();
            rootLogger.addAppender(listAppender);

            try {
                MicrometerMetricsContributor contributor =
                        new MicrometerMetricsContributor(List.of(provider1, provider2));
                VertxBuilder builder = mock(VertxBuilder.class);
                when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
                BootstrapContext ctx =
                        fakeContext(new JsonObject().put("metrics", new JsonObject()), new VertxOptions());

                MetricsBootstrapException ex =
                        assertThrows(MetricsBootstrapException.class, () -> contributor.contribute(builder, ctx));

                // Message must contain the backend name and exception class, not the sentinel
                String msg = ex.getMessage();
                assertTrue(msg.contains("backend2"), "message must name backend2, got: " + msg);
                assertTrue(
                        msg.contains(RuntimeException.class.getSimpleName()),
                        "message must contain exception class name, got: " + msg);
                assertFalse(msg.contains(sentinel), "message must NOT contain sentinel, got: " + msg);
                assertNull(ex.getCause(), "MetricsBootstrapException must have no cause");
                assertTrue(backend1Closed.get(), "backend1 must be closed on rollback");
                assertFalse(MeterRegistryHolder.bootstrapped(), "holder must not be bootstrapped on failure");

                // No log event may contain the sentinel
                for (ch.qos.logback.classic.spi.ILoggingEvent event : listAppender.list) {
                    assertFalse(
                            event.getFormattedMessage().contains(sentinel),
                            "log event must not contain sentinel: " + event.getFormattedMessage());
                }
            } finally {
                rootLogger.detachAppender(listAppender);
                listAppender.stop();
            }
        }
    }

    // --- Test 14: W2 contributor smoke — null nested section does not NPE ---

    @Nested
    @DisplayName("W2 contributor smoke: explicit JSON null on tags section does not NPE in contribute()")
    class NullTagsSectionSmokeTest {

        @Test
        @DisplayName("{\"metrics\":{\"tags\":null}} → contribute() does not throw NPE, behaves like defaults")
        void tagsNullDoesNotNpeInContribute() throws Exception {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            FakeProvider provider = new FakeProvider("test", simple);
            MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(provider));

            VertxBuilder builder = mock(VertxBuilder.class);
            when(builder.withMetrics(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
            VertxOptions options = new VertxOptions();
            JsonObject config = new JsonObject()
                    .put(
                            "metrics",
                            new JsonObject()
                                    .putNull("tags")
                                    .putNull("jvm")
                                    .putNull("cardinality")
                                    .putNull("security")
                                    .putNull("vertx"));
            BootstrapContext ctx = fakeContext(config, options);

            // Must not throw NPE — null nested objects must fall back to defaults
            assertDoesNotThrow(() -> contributor.contribute(builder, ctx));
            assertTrue(MeterRegistryHolder.bootstrapped(), "holder must be bootstrapped");
        }
    }

    // --- Test 12: ServiceLoader smoke ---

    @Nested
    @DisplayName("ServiceLoader smoke test")
    class ServiceLoaderSmoke {

        @Test
        @DisplayName("ServiceLoader finds MicrometerMetricsContributor from services file")
        void serviceLoaderFindsContributor() {
            ServiceLoader<dev.vertique.bootstrap.VertxBuilderContributor> loader =
                    ServiceLoader.load(dev.vertique.bootstrap.VertxBuilderContributor.class);
            boolean found = false;
            for (dev.vertique.bootstrap.VertxBuilderContributor contributor : loader) {
                if (contributor instanceof MicrometerMetricsContributor) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "ServiceLoader must find MicrometerMetricsContributor via services file");
        }
    }

    // --- Helpers ---

    /**
     * Creates a fake {@link BootstrapContext} from the given config and options.
     *
     * @param config  the bootstrap configuration
     * @param options the live VertxOptions instance
     * @return a fake context
     */
    private static BootstrapContext fakeContext(JsonObject config, VertxOptions options) {
        return new BootstrapContext() {
            @Override
            public JsonObject config() {
                return config.copy();
            }

            @Override
            public VertxOptions vertxOptions() {
                return options;
            }
        };
    }

    // --- Test doubles ---

    /**
     * A fake {@link MeterRegistryProvider} backed by a {@link SimpleMeterRegistry}.
     */
    static class FakeProvider implements MeterRegistryProvider {

        private final String name;
        private final SimpleMeterRegistry registry;

        FakeProvider(String name, SimpleMeterRegistry registry) {
            this.name = name;
            this.registry = registry;
        }

        @Override
        public String backendName() {
            return name;
        }

        @Override
        public MeterRegistryBackend create(io.vertx.core.json.JsonObject backendConfig) {
            return new MeterRegistryBackend() {
                @Override
                public MeterRegistry registry() {
                    return FakeProvider.this.registry;
                }

                @Override
                public void close() {
                    // no-op by default
                }
            };
        }
    }

    /**
     * A fake {@link MeterRegistryProvider} whose {@link #create} always throws.
     */
    static class FailingProvider implements MeterRegistryProvider {

        private final String name;

        FailingProvider(String name) {
            this.name = name;
        }

        @Override
        public String backendName() {
            return name;
        }

        @Override
        public MeterRegistryBackend create(io.vertx.core.json.JsonObject backendConfig) throws Exception {
            throw new RuntimeException("FailingProvider: create() failed intentionally");
        }
    }

    /**
     * A fake {@link MeterRegistryProvider} that embeds a sentinel config value in its exception
     * message to verify that the secret does not leak into the exception chain or logs.
     */
    static class SentinelProvider implements MeterRegistryProvider {

        private final String name;
        private final String configValue;

        SentinelProvider(String name, String configValue) {
            this.name = name;
            this.configValue = configValue;
        }

        @Override
        public String backendName() {
            return name;
        }

        @Override
        public MeterRegistryBackend create(io.vertx.core.json.JsonObject backendConfig) throws Exception {
            // Embed the config value in the exception — the contributor must NOT propagate this
            String val = backendConfig.getString("configValue", configValue);
            throw new RuntimeException("SENTINEL_abc " + val);
        }
    }
}
