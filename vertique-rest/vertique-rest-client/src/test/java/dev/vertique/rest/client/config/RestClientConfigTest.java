// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.client.exception.RestClientConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestClientConfig} boundary parsing — the typed model assembled from the
 * section-root-keyed external {@code restClient.{name}} section. The {@code restClient} section is
 * itself the keyed map (client names are the keys at the section root); each entry is parsed into a
 * {@link RestClientConfig} with {@code name} injected as the identity field.
 *
 * <p>These tests close a zero-coverage gap: before this slice, the rest-client config path had no
 * unit tests. They pin the 7 framework→Vert.x {@code webClient} duration-key renames, the pool and
 * circuit-breaker validation bounds, the {@code readTimeoutMs} positivity check, the retry
 * backoff-strategy loadability check, and the client-name key injection.
 */
@DisplayName("RestClientConfig")
class RestClientConfigTest {

    // --- Helpers ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Parses a {@code restClient} section (the keyed map of client name → client config) into the
     * typed index, exercising the boundary parser exactly as the Dagger provider does.
     *
     * @param restClientSection the {@code restClient} section JSON (keys are client names)
     * @return the immutable {@code name -> RestClientConfig} index
     */
    private static Map<String, RestClientConfig> parse(JsonObject restClientSection) {
        return RestClientConfig.indexFromConfig(new JsonObject().put("restClient", restClientSection), configParser());
    }

    /**
     * Finds the single client config of the given name in a parsed index.
     *
     * @param section the {@code restClient} section JSON
     * @param name the client name
     * @return the matching client config
     */
    private static RestClientConfig client(JsonObject section, String name) {
        RestClientConfig cfg = parse(section).get(name);
        if (cfg == null) {
            throw new AssertionError("no client " + name);
        }
        return cfg;
    }

    // --- webClient duration rename (all 7) ---

    @Test
    @DisplayName("normalizes all 7 framework webClient duration keys to their Vert.x option names, preserving values")
    void webClientDurationRename_all7() {
        JsonObject webClient = new JsonObject()
                .put("connectTimeoutMs", 5000)
                .put("idleTimeoutSeconds", 30)
                .put("readIdleTimeoutSeconds", 31)
                .put("writeIdleTimeoutSeconds", 32)
                .put("keepAliveTimeoutSeconds", 33)
                .put("http2KeepAliveTimeoutSeconds", 34)
                .put("sslHandshakeTimeoutSeconds", 35)
                .put("ssl", true); // non-duration field passes through unchanged

        JsonObject section = new JsonObject().put("svc", new JsonObject().put("webClient", webClient));
        JsonObject normalized = client(section, "svc").webClient();

        // Each framework name is renamed to the Vert.x native name with the same value.
        assertEquals(5000, normalized.getValue("connectTimeout"));
        assertEquals(30, normalized.getValue("idleTimeout"));
        assertEquals(31, normalized.getValue("readIdleTimeout"));
        assertEquals(32, normalized.getValue("writeIdleTimeout"));
        assertEquals(33, normalized.getValue("keepAliveTimeout"));
        assertEquals(34, normalized.getValue("http2KeepAliveTimeout"));
        assertEquals(35, normalized.getValue("sslHandshakeTimeout"));

        // The original framework keys are gone.
        assertFalse(normalized.containsKey("connectTimeoutMs"));
        assertFalse(normalized.containsKey("idleTimeoutSeconds"));
        assertFalse(normalized.containsKey("readIdleTimeoutSeconds"));
        assertFalse(normalized.containsKey("writeIdleTimeoutSeconds"));
        assertFalse(normalized.containsKey("keepAliveTimeoutSeconds"));
        assertFalse(normalized.containsKey("http2KeepAliveTimeoutSeconds"));
        assertFalse(normalized.containsKey("sslHandshakeTimeoutSeconds"));

        // Non-duration field passes through untouched.
        assertEquals(true, normalized.getValue("ssl"));
    }

    @Test
    @DisplayName("a client with no webClient section has a null webClient bag")
    void webClient_absentIsNull() {
        JsonObject section = new JsonObject().put("svc", new JsonObject().put("baseUrl", "http://x"));
        assertNull(client(section, "svc").webClient());
    }

    @Test
    @DisplayName(
            "raw Vert.x native duration key in webClient input is rejected with RestClientConfigurationException naming the offending key and its replacement")
    void nativeDurationKeyInWebClient_isRejected() {
        // Given: a webClient bag containing the raw Vert.x native key "connectTimeout" (bypasses
        // the framework's explicit-unit convention; the correct key is "connectTimeoutMs").
        JsonObject webClient = new JsonObject().put("connectTimeout", 5000);
        RestClientConfigurationException ex = assertThrows(
                RestClientConfigurationException.class, () -> RestClientConfig.normalizeWebClientKeys(webClient));
        // The exception message must name both the offending native key and the required
        // explicit-unit key so the operator knows exactly how to fix the config.
        assertTrue(ex.getMessage().contains("connectTimeout"), "message must name the offending native key");
        assertTrue(ex.getMessage().contains("connectTimeoutMs"), "message must name the required explicit-unit key");
    }

    @Test
    @DisplayName(
            "duplicate native+normalized webClient duration keys are rejected (native key present triggers rejection regardless of whether normalized key is also present)")
    void duplicateNativeAndNormalizedDurationKeys_isRejected() {
        // Given: a webClient bag that contains BOTH the normalized key (connectTimeoutMs) AND the
        // native key (connectTimeout). The native key being present is the trigger — regardless of
        // the normalized key also being present.
        JsonObject webClient = new JsonObject().put("connectTimeoutMs", 5000).put("connectTimeout", 6000);
        assertThrows(RestClientConfigurationException.class, () -> RestClientConfig.normalizeWebClientKeys(webClient));
    }

    // --- pool validation bounds ---

    @Test
    @DisplayName("rejects out-of-bounds pool sizes and accepts valid ones")
    void pool_validationBounds() {
        // http1MaxSize must be >= 1
        ConfigurationException e1 = assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put("svc", new JsonObject().put("pool", new JsonObject().put("http1MaxSize", 0)))));
        assertTrue(e1.getMessage().contains("http1MaxSize"), e1.getMessage());

        // http2MaxSize must be >= 1
        assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put("svc", new JsonObject().put("pool", new JsonObject().put("http2MaxSize", 0)))));

        // eventLoopSize must be >= 0
        assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put("svc", new JsonObject().put("pool", new JsonObject().put("eventLoopSize", -1)))));

        // maxLifetimeSeconds must be >= 0
        assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put("svc", new JsonObject().put("pool", new JsonObject().put("maxLifetimeSeconds", -1)))));

        // Valid values accepted; unvalidated fields (maxWaitQueueSize, cleanerPeriodMs) pass through.
        JsonObject valid = new JsonObject()
                .put(
                        "svc",
                        new JsonObject()
                                .put(
                                        "pool",
                                        new JsonObject()
                                                .put("http1MaxSize", 5)
                                                .put("http2MaxSize", 2)
                                                .put("maxWaitQueueSize", -1)
                                                .put("eventLoopSize", 0)
                                                .put("cleanerPeriodMs", 1000)
                                                .put("maxLifetimeSeconds", 0)));
        RestClientPoolConfig pool = client(valid, "svc").pool();
        assertEquals(5, pool.http1MaxSize());
        assertEquals(2, pool.http2MaxSize());
        assertEquals(-1, pool.maxWaitQueueSize());
        assertEquals(0, pool.eventLoopSize());
        assertEquals(1000, pool.cleanerPeriodMs());
        assertEquals(0, pool.maxLifetimeSeconds());
    }

    // --- circuitBreaker validation bounds ---

    @Test
    @DisplayName("rejects out-of-bounds circuit-breaker values and accepts valid ones")
    void circuitBreaker_validationBounds() {
        // maxFailures must be >= 1
        ConfigurationException e1 = assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put("svc", new JsonObject().put("circuitBreaker", new JsonObject().put("maxFailures", 0)))));
        assertTrue(e1.getMessage().contains("maxFailures"), e1.getMessage());

        // timeoutMs must be > 0
        assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put("svc", new JsonObject().put("circuitBreaker", new JsonObject().put("timeoutMs", 0)))));

        // resetTimeoutMs must be > 0
        assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put(
                                "svc",
                                new JsonObject().put("circuitBreaker", new JsonObject().put("resetTimeoutMs", 0)))));

        // retry.maxRetries must be between 0 and 100
        assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put("svc", new JsonObject().put("retry", new JsonObject().put("maxRetries", -1)))));

        // Valid values accepted.
        JsonObject valid = new JsonObject()
                .put(
                        "svc",
                        new JsonObject()
                                .put(
                                        "circuitBreaker",
                                        new JsonObject()
                                                .put("maxFailures", 3)
                                                .put("timeoutMs", 1000)
                                                .put("resetTimeoutMs", 2000))
                                .put("retry", new JsonObject().put("maxRetries", 0)));
        RestClientCircuitBreakerConfig cb = client(valid, "svc").circuitBreaker();
        assertEquals(3, cb.maxFailures());
        assertEquals(1000L, cb.timeoutMs());
        assertEquals(2000L, cb.resetTimeoutMs());
        assertEquals(0, client(valid, "svc").retry().maxRetries());
    }

    // --- readTimeoutMs positivity ---

    @Test
    @DisplayName("rejects non-positive readTimeoutMs, accepts positive, and treats absent as null")
    void readTimeout_mustBePositive() {
        // <= 0 rejected
        ConfigurationException e1 = assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject().put("svc", new JsonObject().put("readTimeoutMs", 0))));
        assertTrue(e1.getMessage().contains("readTimeoutMs"), e1.getMessage());

        // positive accepted
        JsonObject valid = new JsonObject().put("svc", new JsonObject().put("readTimeoutMs", 5000));
        assertEquals(5000L, client(valid, "svc").readTimeoutMs());

        // absent -> null (builder default applies)
        JsonObject absent = new JsonObject().put("svc", new JsonObject().put("baseUrl", "http://x"));
        assertNull(client(absent, "svc").readTimeoutMs());
    }

    // --- retry backoffStrategy loadability ---

    @Test
    @DisplayName("accepts a loadable BackoffStrategy FQCN and rejects an unloadable / non-BackoffStrategy one")
    void retryBackoffStrategy_loadable() {
        // Valid FQCN that implements BackoffStrategy with a public no-arg ctor.
        JsonObject valid = new JsonObject()
                .put(
                        "svc",
                        new JsonObject()
                                .put(
                                        "retry",
                                        new JsonObject().put("backoffStrategy", TestBackoffStrategy.class.getName())));
        assertEquals(
                TestBackoffStrategy.class.getName(),
                client(valid, "svc").retry().backoffStrategy());

        // Unloadable class name.
        ConfigurationException e1 = assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put(
                                "svc",
                                new JsonObject()
                                        .put(
                                                "retry",
                                                new JsonObject().put("backoffStrategy", "com.nope.DoesNotExist")))));
        assertTrue(e1.getMessage().contains("backoffStrategy"), e1.getMessage());

        // A class that exists but does not implement BackoffStrategy.
        assertThrows(
                ConfigurationException.class,
                () -> parse(new JsonObject()
                        .put(
                                "svc",
                                new JsonObject()
                                        .put(
                                                "retry",
                                                new JsonObject().put("backoffStrategy", String.class.getName())))));
    }

    // --- client name key injection ---

    @Test
    @DisplayName("injects the restClient.{name} key into RestClientConfig.name")
    void clientName_keyInjected() {
        JsonObject section = new JsonObject()
                .put("userClient", new JsonObject().put("baseUrl", "http://user"))
                .put("orderClient", new JsonObject().put("baseUrl", "http://order"));
        Map<String, RestClientConfig> index = parse(section);

        assertEquals("userClient", index.get("userClient").name());
        assertEquals("http://user", index.get("userClient").baseUrl());
        assertEquals("orderClient", index.get("orderClient").name());
        assertEquals("http://order", index.get("orderClient").baseUrl());
    }

    @Test
    @DisplayName("an empty restClient section parses to an empty index")
    void emptySection_emptyIndex() {
        assertTrue(parse(new JsonObject()).isEmpty());
    }

    // --- toString redaction (W3: webClient is an open WebClientOptions pass-through bag) ---

    @Test
    @DisplayName("webClient keystore/trust-store passwords are redacted in toString but intact in webClient()")
    void webClientKeystorePasswordRedactedInToString() {
        // The webClient bag is an open Vert.x WebClientOptions pass-through (R9) that can carry
        // ssl key-store / trust-store passwords; the default record toString() rendered it verbatim
        // (gap W3). The redactor must mask credential-bearing keys at every depth.
        JsonObject webClient = new JsonObject()
                .put("ssl", true)
                .put(
                        "trustStoreOptions",
                        new JsonObject().put("path", "/etc/ts.jks").put("password", "TSSEKRIT"))
                .put("keyStorePassword", "KSSEKRIT");

        JsonObject section = new JsonObject().put("svc", new JsonObject().put("webClient", webClient));
        RestClientConfig cfg = client(section, "svc");

        // The live webClient() still carries the real secrets for runtime use.
        assertEquals(
                "TSSEKRIT", cfg.webClient().getJsonObject("trustStoreOptions").getString("password"));
        assertEquals("KSSEKRIT", cfg.webClient().getString("keyStorePassword"));

        String rendered = cfg.toString();
        assertFalse(rendered.contains("TSSEKRIT"), "trust-store password must not appear in toString (W3)");
        assertFalse(rendered.contains("KSSEKRIT"), "key-store password must not appear in toString (W3)");
        // Non-secret webClient option stays visible for debuggability.
        assertTrue(rendered.contains("ssl"), "non-secret webClient option stays visible");
    }

    @Test
    @DisplayName("baseUrl authority userinfo is redacted in toString but intact in baseUrl()")
    void baseUrlUserinfoRedacted() {
        JsonObject section = new JsonObject().put("svc", new JsonObject().put("baseUrl", "https://u:SEKRIT@h/path"));
        RestClientConfig cfg = client(section, "svc");

        // The live baseUrl() still returns the real value for runtime use.
        assertEquals("https://u:SEKRIT@h/path", cfg.baseUrl(), "live baseUrl() must return the real value");

        String rendered = cfg.toString();
        assertFalse(rendered.contains("SEKRIT"), "baseUrl userinfo password must not appear in toString");
        assertTrue(rendered.contains("/path"), "baseUrl path stays visible for debuggability");
    }
}
