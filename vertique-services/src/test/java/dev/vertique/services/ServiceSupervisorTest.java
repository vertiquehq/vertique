// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.deploy.SupervisionConfig;
import dev.vertique.deploy.VerticleSupervisor;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServicesConfig;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ServiceSupervisor} as a thin adapter over {@link VerticleSupervisor}.
 *
 * <p>Verifies that contract-to-deployment-name translation, config loading, and all delegation
 * calls behave correctly. Uses a Mockito mock for {@link VerticleSupervisor} — behavioural
 * tests for restart scheduling, budget exhaustion, and backoff live in
 * {@code VerticleSupervisorTest} in the {@code deploy} module.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceSupervisor")
class ServiceSupervisorTest {

    // --- Contract Fixtures ---

    /** Dummy contract interface used as a key in the supervisor's contract map. */
    interface TestService {
        /** Dummy method. */
        void run();
    }

    /** Second dummy contract for multi-service tests. */
    interface OtherService {
        /** Dummy method. */
        void ping();
    }

    // --- Fields ---

    @Mock
    VerticleSupervisor verticleSupervisor;

    ServiceSupervisor supervisor;

    // --- Setup ---

    @BeforeEach
    void setUp() {
        supervisor = new ServiceSupervisor(verticleSupervisor, Map.of());
    }

    // --- Helpers ---

    /**
     * Builds the typed {@code (type, name) -> ServiceConfig} index from a root config object via the
     * boundary parser, exactly as the Dagger provider does.
     *
     * @param rootConfig the root application config (may contain a {@code services} section)
     * @return the parsed service config index
     */
    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    private static Map<ServicesConfig.ServiceKey, ServiceConfig> indexFor(JsonObject rootConfig) {
        return ServicesConfig.fromConfig(rootConfig, configParser()).index();
    }

    /**
     * Builds a supervision config root JSON block at path
     * {@code services.contracts.test.svc.supervision}.
     *
     * @param maxRestarts maximum restarts allowed
     * @param initialBackoffMs initial backoff in milliseconds
     * @param maxBackoffMs maximum backoff cap in milliseconds
     * @return a {@link JsonObject} with the nested supervision config
     */
    private static JsonObject supervisionConfig(int maxRestarts, int initialBackoffMs, int maxBackoffMs) {
        return new JsonObject()
                .put(
                        "services",
                        new JsonObject()
                                .put(
                                        "contracts",
                                        new JsonObject()
                                                .put(
                                                        "test",
                                                        new JsonObject()
                                                                .put(
                                                                        "svc",
                                                                        new JsonObject()
                                                                                .put(
                                                                                        "supervision",
                                                                                        new JsonObject()
                                                                                                .put(
                                                                                                        "maxRestarts",
                                                                                                        maxRestarts)
                                                                                                .put("withinMs", 60000)
                                                                                                .put(
                                                                                                        "initialBackoffMs",
                                                                                                        initialBackoffMs)
                                                                                                .put(
                                                                                                        "maxBackoffMs",
                                                                                                        maxBackoffMs))))));
    }

    // --- Tests ---

    /**
     * After {@code watch()}, {@code isAvailable()} must delegate to the underlying supervisor and
     * return its result.
     */
    @Test
    @DisplayName("isAvailable delegates to VerticleSupervisor after watch()")
    void shouldBeAvailableAfterWatch() {
        String deploymentName = "dispatch-service:test/svc#" + TestService.class.getName();
        when(verticleSupervisor.isAvailable(deploymentName)).thenReturn(true);

        supervisor.watch(TestService.class, "test", "svc", deploymentName, "dep-1", () -> {});

        assertTrue(supervisor.isAvailable(TestService.class));
        verify(verticleSupervisor).supervise(eq(deploymentName), eq("dep-1"), any(SupervisionConfig.class), any());
    }

    /**
     * An unregistered contract must return {@code true} without calling the underlying supervisor
     * (fail-open behaviour).
     */
    @Test
    @DisplayName("isAvailable returns true for unregistered contract (fail-open)")
    void shouldBeAvailableWhenNotRegistered() {
        assertTrue(supervisor.isAvailable(TestService.class));
        verifyNoInteractions(verticleSupervisor);
    }

    /**
     * {@code isAvailable()} must return {@code false} when the underlying supervisor reports the
     * deployment as unavailable.
     */
    @Test
    @DisplayName("isAvailable returns false when VerticleSupervisor says unavailable")
    void isAvailable_returnsFalseWhenSupervisorSaysFalse() {
        String deploymentName = "dispatch-service:test/svc#" + TestService.class.getName();
        when(verticleSupervisor.isAvailable(deploymentName)).thenReturn(false);

        supervisor.watch(TestService.class, "test", "svc", deploymentName, "dep-1", () -> {});

        assertFalse(supervisor.isAvailable(TestService.class));
    }

    /**
     * {@code reportFatalError()} must translate the contract to a deployment name and delegate to
     * {@link VerticleSupervisor#reportFatalError(String, Throwable)}.
     */
    @Test
    @DisplayName("reportFatalError delegates to VerticleSupervisor with deployment name")
    void shouldDelegateReportFatalError() {
        String deploymentName = "dispatch-service:test/svc#" + TestService.class.getName();
        supervisor.watch(TestService.class, "test", "svc", deploymentName, "dep-1", () -> {});

        Throwable error = new RuntimeException("boom");
        supervisor.reportFatalError(TestService.class, error);

        verify(verticleSupervisor).reportFatalError(deploymentName, error);
    }

    /**
     * {@code reportFatalError()} for an unregistered contract must be a no-op — no interaction
     * with the underlying supervisor.
     */
    @Test
    @DisplayName("reportFatalError is no-op for unregistered contract")
    void shouldIgnoreReportFatalErrorForUnknownContract() {
        supervisor.reportFatalError(TestService.class, new RuntimeException("boom"));
        verifyNoInteractions(verticleSupervisor);
    }

    /**
     * {@code deregister()} must remove the contract mapping and call
     * {@link VerticleSupervisor#unsupervise(String)} with the correct deployment name.
     */
    @Test
    @DisplayName("deregister delegates to VerticleSupervisor.unsupervise()")
    void shouldDelegateDeregister() {
        String deploymentName = "dispatch-service:test/svc#" + TestService.class.getName();
        supervisor.watch(TestService.class, "test", "svc", deploymentName, "dep-1", () -> {});

        supervisor.deregister(TestService.class);

        verify(verticleSupervisor).unsupervise(deploymentName);
        // After deregister, isAvailable should be fail-open (no longer calls supervisor)
        reset(verticleSupervisor);
        assertTrue(supervisor.isAvailable(TestService.class));
        verifyNoInteractions(verticleSupervisor);
    }

    /**
     * {@code reportRedeployFailure()} must translate the contract to a deployment name and
     * delegate to {@link VerticleSupervisor#reportRedeployFailure(String, Throwable)}.
     */
    @Test
    @DisplayName("reportRedeployFailure delegates to VerticleSupervisor with deployment name")
    void shouldDelegateReportRedeployFailure() {
        String deploymentName = "dispatch-service:test/svc#" + TestService.class.getName();
        supervisor.watch(TestService.class, "test", "svc", deploymentName, "dep-1", () -> {});

        Throwable cause = new RuntimeException("deploy failed");
        supervisor.reportRedeployFailure(TestService.class, cause);

        verify(verticleSupervisor).reportRedeployFailure(deploymentName, cause);
    }

    /**
     * {@code watch()} must call {@link VerticleSupervisor#supervise} with the exact
     * {@link SupervisionConfig} values loaded from hierarchical application config.
     */
    @Test
    @DisplayName("watch() passes SupervisionConfig from hierarchical config to VerticleSupervisor")
    void shouldApplyPerServiceSupervisionConfig() {
        JsonObject config = supervisionConfig(3, 500, 5000);
        ServiceSupervisor sup = new ServiceSupervisor(verticleSupervisor, indexFor(config));
        String deploymentName = "dispatch-service:test/svc#" + TestService.class.getName();

        sup.watch(TestService.class, "test", "svc", deploymentName, "dep-1", () -> {});

        ArgumentCaptor<SupervisionConfig> captor = ArgumentCaptor.forClass(SupervisionConfig.class);
        verify(verticleSupervisor).supervise(eq(deploymentName), eq("dep-1"), captor.capture(), any());

        SupervisionConfig captured = captor.getValue();
        assertEquals(3, captured.maxRestarts());
        assertEquals(60_000L, captured.withinMs());
        assertEquals(500L, captured.initialBackoffMs());
        assertEquals(5_000L, captured.maxBackoffMs());
    }

    /**
     * When no supervision config is present in the application config, {@code watch()} must pass
     * {@link SupervisionConfig#DEFAULT} to {@link VerticleSupervisor#supervise}.
     */
    @Test
    @DisplayName("watch() uses SupervisionConfig.DEFAULT when no config is present")
    void shouldFallBackToDefaultsWhenNoConfig() {
        String deploymentName = "dispatch-service:test/svc#" + TestService.class.getName();
        supervisor.watch(TestService.class, "test", "svc", deploymentName, "dep-1", () -> {});

        ArgumentCaptor<SupervisionConfig> captor = ArgumentCaptor.forClass(SupervisionConfig.class);
        verify(verticleSupervisor).supervise(eq(deploymentName), eq("dep-1"), captor.capture(), any());

        assertEquals(SupervisionConfig.DEFAULT, captor.getValue());
    }

    /**
     * If {@code supervise()} throws (coherence check failure), no contract mapping must remain
     * in the supervisor. Subsequent {@code isAvailable()} returns {@code true} (fail-open).
     */
    @Test
    @DisplayName("watch() coherence failure leaves no stale mapping")
    void watch_coherenceFailure_noStaleMapping() {
        String deploymentName = "dispatch-service:test/svc#" + TestService.class.getName();
        doThrow(new IllegalStateException("coherence mismatch"))
                .when(verticleSupervisor)
                .supervise(eq(deploymentName), eq("dep-1"), any(SupervisionConfig.class), any());

        assertThrows(
                IllegalStateException.class,
                () -> supervisor.watch(TestService.class, "test", "svc", deploymentName, "dep-1", () -> {}));

        // Contract mapping must not exist — isAvailable should be fail-open without calling supervisor
        reset(verticleSupervisor);
        assertTrue(supervisor.isAvailable(TestService.class));
        verifyNoInteractions(verticleSupervisor);
    }

    /**
     * {@code serviceAvailability()} must return a map keyed by service names (not deployment
     * names) with availability delegated to the underlying supervisor per contract.
     */
    @Test
    @DisplayName("serviceAvailability() returns service names mapped to supervisor availability")
    void serviceAvailability_returnsServiceNames() {
        String name1 = "dispatch-service:test/runner#" + TestService.class.getName();
        String name2 = "dispatch-service:test/sorter#" + OtherService.class.getName();

        when(verticleSupervisor.isAvailable(name1)).thenReturn(true);
        when(verticleSupervisor.isAvailable(name2)).thenReturn(false);

        supervisor.watch(TestService.class, "test", "runner", name1, "dep-1", () -> {});
        supervisor.watch(OtherService.class, "test", "sorter", name2, "dep-2", () -> {});

        var availability = supervisor.serviceAvailability();

        assertEquals(2, availability.size());
        assertTrue(availability.get("runner"), "runner should be available");
        assertFalse(availability.get("sorter"), "sorter should be unavailable");
        // Keys must be service names, not deployment names
        assertFalse(availability.containsKey(name1));
        assertFalse(availability.containsKey(name2));
    }
}
