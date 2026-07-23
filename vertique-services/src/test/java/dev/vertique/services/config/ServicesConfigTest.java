// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.deploy.SupervisionConfig;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServicesConfig} boundary parsing — the typed model assembled from the
 * keyed-object external {@code services} section
 * ({@code services.contracts.{namespace}.{name}}, operations under {@code operations.{operation}}).
 *
 * <p>Verifies that the namespace-keyed structure under {@code contracts} is parsed into typed records
 * with {@code namespace} and {@code name} injected as identity fields, that the reserved {@code _}
 * key resolves to the empty default namespace, that operation keys are injected, that partial
 * supervision objects default from {@link SupervisionConfig#DEFAULT}, that absent fields fall back to
 * their defaults, that a blank {@code name} is rejected at construction (a blank namespace is
 * allowed), and that the global {@code sendTimeoutMs} scalar is read.
 */
@DisplayName("ServicesConfig")
class ServicesConfigTest {

    // --- Helpers ---

    /**
     * Builds a {@link ServicesConfig} from the {@code services} section of a root config object,
     * exercising the boundary parser exactly as the Dagger provider does.
     *
     * @param services the {@code services} section JSON
     * @return the parsed typed config
     */
    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    private static ServicesConfig parse(JsonObject services) {
        return ServicesConfig.fromConfig(new JsonObject().put("services", services), configParser());
    }

    /**
     * Wraps a per-namespace service-group map under the {@code contracts} key, matching the external
     * shape {@code services.contracts.{namespace}.{name}}.
     *
     * @param namespaceGroups the per-namespace groups keyed by namespace ({@code _} for the empty
     *     default namespace)
     * @return a JSON object {@code {"contracts": namespaceGroups}}
     */
    private static JsonObject contracts(JsonObject namespaceGroups) {
        return new JsonObject().put("contracts", namespaceGroups);
    }

    /**
     * Finds the single service of the given namespace and name in a parsed config.
     *
     * @param config the parsed services config
     * @param namespace the service namespace
     * @param name the service name
     * @return the matching service config
     */
    private static ServiceConfig service(ServicesConfig config, String namespace, String name) {
        return config.services().stream()
                .filter(s -> s.namespace().equals(namespace) && s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no service " + namespace + "/" + name));
    }

    // --- Tests ---

    @Test
    @DisplayName("parses namespace-keyed services with namespace and name injected, _ maps to empty namespace")
    void parsesNamespaceKeyedServices() {
        JsonObject services = contracts(new JsonObject()
                .put(
                        "default",
                        new JsonObject()
                                .put("orders", new JsonObject().put("instances", 2))
                                .put("billing", new JsonObject().put("worker", true)))
                .put("internal", new JsonObject().put("audit", new JsonObject()))
                .put("_", new JsonObject().put("rootless", new JsonObject())));

        ServicesConfig config = parse(services);

        assertEquals(4, config.services().size());
        ServiceConfig orders = service(config, "default", "orders");
        assertEquals("default", orders.namespace());
        assertEquals("orders", orders.name());
        assertEquals(2, orders.instances());

        ServiceConfig billing = service(config, "default", "billing");
        assertEquals("default", billing.namespace());
        assertEquals("billing", billing.name());
        assertTrue(billing.worker());

        ServiceConfig audit = service(config, "internal", "audit");
        assertEquals("internal", audit.namespace());
        assertEquals("audit", audit.name());

        // The reserved _ key maps to the empty default namespace.
        ServiceConfig rootless = service(config, "", "rootless");
        assertEquals("", rootless.namespace());
        assertEquals("rootless", rootless.name());
        assertNotNull(config.index().get(new ServicesConfig.ServiceKey("", "rootless")));
    }

    @Test
    @DisplayName("injects the operation key into ServiceOperationConfig.operation")
    void operationsKeyInjected() {
        JsonObject services = contracts(new JsonObject()
                .put(
                        "default",
                        new JsonObject()
                                .put(
                                        "orders",
                                        new JsonObject()
                                                .put(
                                                        "operations",
                                                        new JsonObject()
                                                                .put(
                                                                        "submit",
                                                                        new JsonObject()
                                                                                .put("sendTimeoutMs", 1000))))));

        ServicesConfig config = parse(services);

        ServiceConfig orders = service(config, "default", "orders");
        assertEquals(1, orders.operations().size());
        ServiceOperationConfig submit = orders.operations().get(0);
        assertEquals("submit", submit.operation());
        assertEquals(1000L, submit.sendTimeoutMs());
    }

    @Test
    @DisplayName("partial supervision object fills missing fields from DEFAULT")
    void supervisionPartial_fillsDefaults() {
        JsonObject services = contracts(new JsonObject()
                .put(
                        "default",
                        new JsonObject()
                                .put(
                                        "orders",
                                        new JsonObject().put("supervision", new JsonObject().put("maxRestarts", 9)))));

        ServicesConfig config = parse(services);

        SupervisionConfig supervision = service(config, "default", "orders").supervision();
        assertEquals(9, supervision.maxRestarts());
        assertEquals(SupervisionConfig.DEFAULT.withinMs(), supervision.withinMs());
        assertEquals(SupervisionConfig.DEFAULT.initialBackoffMs(), supervision.initialBackoffMs());
        assertEquals(SupervisionConfig.DEFAULT.maxBackoffMs(), supervision.maxBackoffMs());
    }

    @Test
    @DisplayName("absent fields default: instances=1, worker=false, supervision DEFAULT, empty operations")
    void defaults_whenAbsent() {
        JsonObject services =
                contracts(new JsonObject().put("default", new JsonObject().put("orders", new JsonObject())));

        ServicesConfig config = parse(services);

        ServiceConfig orders = service(config, "default", "orders");
        assertEquals(1, orders.instances());
        assertFalse(orders.worker());
        assertEquals(SupervisionConfig.DEFAULT, orders.supervision());
        assertNull(orders.sendTimeoutMs());
        assertTrue(orders.operations().isEmpty());
    }

    @Test
    @DisplayName("blank name is rejected at parse by the compact constructor; a blank namespace is allowed")
    void blankName_rejected() {
        // Blank name is rejected regardless of namespace.
        assertThrows(
                ConfigurationException.class,
                () -> new ServiceConfig("default", "  ", 1, false, null, SupervisionConfig.DEFAULT, List.of()));
        // A blank (empty) namespace is now valid — the empty default namespace.
        ServiceConfig emptyNamespace =
                new ServiceConfig("", "orders", 1, false, null, SupervisionConfig.DEFAULT, List.of());
        assertEquals("", emptyNamespace.namespace());
        assertEquals("orders", emptyNamespace.name());
    }

    @Test
    @DisplayName("global services.sendTimeoutMs scalar is read; absent yields null")
    void globalSendTimeoutMs_read() {
        JsonObject withGlobal = new JsonObject()
                .put("sendTimeoutMs", 5000)
                .mergeIn(contracts(new JsonObject().put("default", new JsonObject().put("orders", new JsonObject()))));
        ServicesConfig config = parse(withGlobal);
        assertEquals(5000L, config.sendTimeoutMs());
        // the scalar key is not parsed as a namespace group
        assertEquals(1, config.services().size());
        assertNotNull(service(config, "default", "orders"));

        ServicesConfig absent =
                parse(contracts(new JsonObject().put("default", new JsonObject().put("orders", new JsonObject()))));
        assertNull(absent.sendTimeoutMs());
    }

    // --- W2: fail-fast on malformed structure (no silent skip / default fallback) ---

    @Test
    @DisplayName("a namespace group bound to a non-object (scalar/array) fails fast rather than being silently skipped")
    void nonObjectNamespaceGroup_failsFast() {
        // Scalar namespace group.
        JsonObject scalarGroup = contracts(new JsonObject().put("default", "not-an-object"));
        ConfigurationException scalarEx = assertThrows(ConfigurationException.class, () -> parse(scalarGroup));
        assertTrue(
                scalarEx.getMessage().contains("services.contracts.default"),
                "message should name the offending path, got: " + scalarEx.getMessage());

        // Array namespace group.
        JsonObject arrayGroup = contracts(new JsonObject().put("default", new io.vertx.core.json.JsonArray().add("a")));
        ConfigurationException arrayEx = assertThrows(ConfigurationException.class, () -> parse(arrayGroup));
        assertTrue(
                arrayEx.getMessage().contains("services.contracts.default"),
                "message should name the offending path, got: " + arrayEx.getMessage());
    }

    @Test
    @DisplayName("a contracts section bound to a non-object (scalar/array) fails fast")
    void nonObjectContractsSection_failsFast() {
        // contracts as a scalar.
        JsonObject scalarContracts = new JsonObject().put("contracts", "not-an-object");
        assertThrows(ConfigurationException.class, () -> parse(scalarContracts));

        // contracts as an array.
        JsonObject arrayContracts = new JsonObject().put("contracts", new io.vertx.core.json.JsonArray().add("a"));
        assertThrows(ConfigurationException.class, () -> parse(arrayContracts));
    }

    @Test
    @DisplayName("a valid namespace group and contracts section parse without error")
    void validStructure_accepted() {
        JsonObject services =
                contracts(new JsonObject().put("default", new JsonObject().put("orders", new JsonObject())));
        ServicesConfig config = parse(services);
        assertEquals(1, config.services().size());
        assertNotNull(service(config, "default", "orders"));
    }

    // --- W4: per-operation override bounds validation (each rule applies only when the field is present) ---

    @Test
    @DisplayName("a non-positive operation sendTimeoutMs is rejected; a positive value is accepted")
    void negativeOperationSendTimeout_rejected() {
        assertThrows(ConfigurationException.class, () -> new ServiceOperationConfig("op", -1L, null, null, null));
        assertThrows(ConfigurationException.class, () -> new ServiceOperationConfig("op", 0L, null, null, null));
        ServiceOperationConfig valid = new ServiceOperationConfig("op", 1L, null, null, null);
        assertEquals(1L, valid.sendTimeoutMs());
        // absent (null) is allowed
        assertNull(new ServiceOperationConfig("op", null, null, null, null).sendTimeoutMs());
    }

    @Test
    @DisplayName("a non-positive timeout valueMs is rejected; a positive value is accepted")
    void zeroTimeoutValueMs_rejected() {
        assertThrows(ConfigurationException.class, () -> new TimeoutOverride(0L));
        assertThrows(ConfigurationException.class, () -> new TimeoutOverride(-5L));
        assertEquals(100L, new TimeoutOverride(100L).valueMs());
        assertNull(new TimeoutOverride(null).valueMs());
    }

    @Test
    @DisplayName("a circuitBreaker maxFailures below 1 is rejected; valid bounds accepted")
    void cbMaxFailuresBelowOne_rejected() {
        assertThrows(ConfigurationException.class, () -> new CircuitBreakerOverride(0, null, null));
        assertThrows(ConfigurationException.class, () -> new CircuitBreakerOverride(-1, null, null));
        assertThrows(ConfigurationException.class, () -> new CircuitBreakerOverride(null, 0L, null));
        assertThrows(ConfigurationException.class, () -> new CircuitBreakerOverride(null, null, 0L));
        CircuitBreakerOverride valid = new CircuitBreakerOverride(1, 100L, 200L);
        assertEquals(1, valid.maxFailures());
        assertEquals(100L, valid.timeoutMs());
        assertEquals(200L, valid.resetTimeoutMs());
        // all absent is allowed
        CircuitBreakerOverride absent = new CircuitBreakerOverride(null, null, null);
        assertNull(absent.maxFailures());
    }

    @Test
    @DisplayName("a retry maxRetries below 0 is rejected; valid bounds accepted")
    void retryNegativeMaxRetries_rejected() {
        assertThrows(ConfigurationException.class, () -> new RetryOverride(-1, null, null, null));
        assertThrows(ConfigurationException.class, () -> new RetryOverride(null, -1L, null, null));
        assertThrows(ConfigurationException.class, () -> new RetryOverride(null, null, null, -1L));
        // zero maxRetries / zero delay / zero maxDelay are valid (>= 0)
        RetryOverride valid = new RetryOverride(0, 0L, 1.0, 0L);
        assertEquals(0, valid.maxRetries());
        assertEquals(0L, valid.delayMs());
        assertEquals(0L, valid.maxDelayMs());
        assertNull(new RetryOverride(null, null, null, null).maxRetries());
    }

    @Test
    @DisplayName("a retry backoffMultiplier below 1.0 is rejected; >= 1.0 is accepted")
    void backoffMultiplierBelowOne_rejected() {
        assertThrows(ConfigurationException.class, () -> new RetryOverride(null, null, 0.99, null));
        assertThrows(ConfigurationException.class, () -> new RetryOverride(null, null, 0.0, null));
        assertEquals(1.0, new RetryOverride(null, null, 1.0, null).backoffMultiplier());
        assertEquals(2.5, new RetryOverride(null, null, 2.5, null).backoffMultiplier());
        assertNull(new RetryOverride(null, null, null, null).backoffMultiplier());
    }

    @Test
    @DisplayName("a non-positive ServiceConfig sendTimeoutMs is rejected; a positive value and null are accepted")
    void serviceConfigSendTimeout_rejectedWhenNonPositive() {
        assertThrows(
                ConfigurationException.class,
                () -> new ServiceConfig("default", "orders", 1, false, 0L, SupervisionConfig.DEFAULT, List.of()));
        assertThrows(
                ConfigurationException.class,
                () -> new ServiceConfig("default", "orders", 1, false, -1L, SupervisionConfig.DEFAULT, List.of()));
        ServiceConfig valid =
                new ServiceConfig("default", "orders", 1, false, 100L, SupervisionConfig.DEFAULT, List.of());
        assertEquals(100L, valid.sendTimeoutMs());
        assertNull(new ServiceConfig("default", "orders", 1, false, null, SupervisionConfig.DEFAULT, List.of())
                .sendTimeoutMs());
    }

    @Test
    @DisplayName("a non-positive global ServicesConfig sendTimeoutMs is rejected; positive and null are accepted")
    void servicesConfigSendTimeout_rejectedWhenNonPositive() {
        assertThrows(ConfigurationException.class, () -> new ServicesConfig(0L, List.of()));
        assertThrows(ConfigurationException.class, () -> new ServicesConfig(-1L, List.of()));
        assertEquals(5000L, new ServicesConfig(5000L, List.of()).sendTimeoutMs());
        assertNull(new ServicesConfig(null, List.of()).sendTimeoutMs());
    }
}
