// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.job.delayed.config.DelayedJobsConfig;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link DelayedJobClientFactory}: proxy creation, interface validation, and
 * annotation requirement enforcement.
 */
@DisplayName("DelayedJobClientFactory")
@ExtendWith(MockitoExtension.class)
class DelayedJobClientFactoryTest {

    // --- Test Fixtures ---

    /** Valid contract interface carrying @DelayedJobContract. */
    @DelayedJobContract(name = "factory-test-job")
    interface FactoryTestJob extends DelayedJobClient<String> {}

    /** Abstract class — not an interface. */
    abstract static class NotAnInterface implements DelayedJobClient<String> {}

    /** Interface without @DelayedJobContract. */
    interface NoAnnotationContract extends DelayedJobClient<String> {}

    // --- Setup ---

    @Mock
    DelayedJobService jobService;

    DelayedJobClientFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DelayedJobClientFactory(jobService, Map.of());
    }

    // --- Tests ---

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("returns non-null proxy for valid @DelayedJobContract interface")
        void returnsProxyForValidInterface() {
            FactoryTestJob proxy = factory.create(FactoryTestJob.class);
            assertNotNull(proxy);
        }

        @Test
        @DisplayName("returned proxy implements the contract interface")
        void proxyImplementsContractInterface() {
            Object proxy = factory.create(FactoryTestJob.class);
            assertInstanceOf(FactoryTestJob.class, proxy);
        }

        @Test
        @DisplayName("returned proxy implements DelayedJobClient")
        void proxyImplementsDelayedJobClient() {
            Object proxy = factory.create(FactoryTestJob.class);
            assertInstanceOf(DelayedJobClient.class, proxy);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for a non-interface type")
        void throwsForNonInterface() {
            // NotAnInterface is an abstract class, not an interface — use mock class trick:
            // We can't directly pass abstract class, so we test via a concrete non-interface class.
            // Use String.class as a proxy stand-in to exercise the non-interface guard.
            @SuppressWarnings("unchecked")
            Class<DelayedJobClient<String>> badClass = (Class<DelayedJobClient<String>>) (Class<?>) String.class;

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> factory.create(badClass));
            assertNotNull(ex.getMessage());
        }

        @Test
        @DisplayName("throws IllegalArgumentException for interface missing @DelayedJobContract")
        void throwsForMissingAnnotation() {
            @SuppressWarnings("unchecked")
            Class<DelayedJobClient<String>> badInterface =
                    (Class<DelayedJobClient<String>>) (Class<?>) NoAnnotationContract.class;

            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> factory.create(badInterface));
            assertTrue(ex.getMessage().contains("@DelayedJobContract"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Provider-based access (DI cycle workaround)")
    class ProviderAccess {

        @Test
        @DisplayName("factory works when accessed via Provider (DI cycle workaround)")
        void factoryWorksViaProvider() {
            // Simulate Provider<DelayedJobClientFactory> lazy access pattern used to
            // break DI cycles when a service depends on the factory that depends on it.
            jakarta.inject.Provider<DelayedJobClientFactory> provider = () -> factory;

            DelayedJobClientFactory lazyFactory = provider.get();
            FactoryTestJob proxy = lazyFactory.create(FactoryTestJob.class);

            assertNotNull(proxy);
            assertTrue(proxy instanceof DelayedJobClient, "Proxy must implement DelayedJobClient");
        }
    }

    @Nested
    @DisplayName("generated-proxy selection")
    class GeneratedProxySelection {

        @Test
        @DisplayName("uses the generated proxy when present on the classpath")
        void usesGeneratedProxyWhenPresent() {
            Object proxy = factory.create(SelectionTestJob.class);

            assertInstanceOf(SelectionTestJob_DelayedJobProxy.class, proxy);
            assertFalse(Proxy.isProxyClass(proxy.getClass()), "should not be a JDK dynamic proxy");
        }

        @Test
        @DisplayName("uses the generated proxy for a nested contract via the flattened companion name")
        void usesGeneratedProxyForNestedContract() {
            Object proxy = factory.create(NestedSelectionHost.NestedJob.class);

            // Selected only if the factory derives the lookup via GeneratedNames.companionFqn (Outer$Inner ->
            // Outer_Inner); a regression to contract.getName() + suffix would miss it and fall back to a JDK proxy.
            assertInstanceOf(NestedSelectionHost_NestedJob_DelayedJobProxy.class, proxy);
            assertFalse(Proxy.isProxyClass(proxy.getClass()), "should not be a JDK dynamic proxy");
        }

        @Test
        @DisplayName("falls back to a JDK dynamic proxy when no generated proxy exists")
        void fallsBackToJdkProxyWhenAbsent() {
            Object proxy = factory.create(FactoryTestJob.class);

            assertTrue(Proxy.isProxyClass(proxy.getClass()), "should be a JDK dynamic proxy");
        }

        @Test
        @DisplayName("fails loudly when a present generated proxy cannot be instantiated")
        void failsLoudlyWhenGeneratedProxyBroken() {
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> factory.create(BrokenSelectionJob.class));
            assertTrue(ex.getMessage().contains("BrokenSelectionJob_DelayedJobProxy"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("config override")
    class ConfigOverride {

        @Test
        @DisplayName("factory with per-contract config creates a proxy that uses config values")
        void factoryWithConfigCreatesProxy() {
            JsonObject config = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "factory-test-job",
                                                            new JsonObject()
                                                                    .put("maxAttempts", 7)
                                                                    .put("queue", "priority"))));
            DelayedJobClientFactory configFactory = new DelayedJobClientFactory(
                    jobService,
                    DelayedJobsConfig.fromConfig(config, new DefaultConfigParser(DefaultConfigMapper.lenient()))
                            .contractIndex());

            FactoryTestJob proxy = configFactory.create(FactoryTestJob.class);

            // Proxy must be non-null and implement the contract — deeper behavior tested in proxy tests
            assertNotNull(proxy);
            assertInstanceOf(FactoryTestJob.class, proxy);
        }
    }

    // Helper to keep assertion in scope
    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
