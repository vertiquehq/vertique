// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

import static dev.vertique.config.testing.ExceptionChainAssertions.containsAnywhere;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.core.credential.BasicAuthenticationCredential;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.github.nagyesta.lowkeyvault.testcontainers.LowkeyVaultContainer;
import com.github.nagyesta.lowkeyvault.testcontainers.LowkeyVaultContainerBuilder;
import dev.vertique.config.placeholder.PlaceholderResolver;
import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests for {@link AzureKeyVaultPropertySourceFactory} and
 * {@link AzureKeyVaultPropertySource} against a real Azure Key Vault test double running
 * in Lowkey Vault ({@code nagyesta/lowkey-vault}) via Testcontainers.
 *
 * <h2>Lowkey Vault</h2>
 * <p>Lowkey Vault ({@code com.github.nagyesta.lowkey-vault:lowkey-vault-testcontainers:7.3.0})
 * is an Azure Key Vault test double that serves HTTPS with a self-signed certificate on port
 * 8443. The container image is {@code nagyesta/lowkey-vault} (Docker Hub).
 *
 * <h2>Trust and credentials</h2>
 * <p>The {@link com.github.nagyesta.lowkeyvault.testcontainers.LowkeyVaultClientFactory}
 * provided by the container wraps the Azure SDK {@code SecretClientBuilder} with:
 * <ul>
 *   <li>An Apache HTTP client that trusts the container's self-signed certificate.</li>
 *   <li>{@code disableChallengeResourceVerification()} — required because Lowkey Vault
 *       serves a non-Azure challenge.</li>
 *   <li>A {@code BasicAuthenticationCredential} using the container's dummy username/password
 *       (Lowkey Vault accepts any well-formed credential).</li>
 * </ul>
 * <p>This trust configuration is confined to the test — it is injected via the
 * {@link AzureKeyVaultPropertySourceFactory} gateway-factory seam and the package-private
 * {@link SdkKeyVaultGateway#SdkKeyVaultGateway(String, SecretClient)} test-seam constructor.
 * The production {@link SdkKeyVaultGateway#SdkKeyVaultGateway(String, AzureConnectionSettings)}
 * constructor is never called from this test.
 *
 * <h2>Production path boundary</h2>
 * <p>The production builder code in {@link SdkKeyVaultGateway#buildClient(AzureConnectionSettings)}
 * uses {@code DefaultAzureCredential}/{@code ManagedIdentityCredential} with real TLS and is
 * not exercised against Lowkey Vault by design: those credential types require a real Azure
 * endpoint or a local CLI session. The full pipeline — prefix filtering, normalization,
 * validation, 404-vs-error semantics, and {@link PlaceholderResolver} integration — is verified
 * here by exercising the real {@link AzureKeyVaultPropertySource} with a real {@link SecretClient}
 * pointed at Lowkey Vault through the gateway-factory seam.
 *
 * <h2>Container seeding</h2>
 * <p>Secrets are seeded in {@link #startContainer()} via the same {@code SecretClient} that
 * Lowkey Vault's own factory builds — no additional HTTP calls or management API is required.
 * The vault name is {@code "it"} (matches the container's {@code vaultNames} set) and the
 * default vault base URL is {@code https://it.localhost:<mapped-port>}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Seeded secret resolves end-to-end through {@link AzureKeyVaultPropertySource}</li>
 *   <li>Normalization: {@code "db.api.key"} with prefix {@code "db."} → strips → {@code "api.key"}
 *       → normalizes → {@code "api-key"} → resolves the seeded sentinel</li>
 *   <li>404 → not-found returns {@link java.util.Optional#empty()}</li>
 *   <li>{@link PlaceholderResolver} engine e2e: fallback applied for missing key, and sentinel
 *       resolved for present key, via a two-source list</li>
 *   <li>Error-vs-not-found: wrong vault URL on an unroutable port → throws
 *       {@link ConfigPropertySourceException} naming the key; sentinel absent</li>
 *   <li>Disabled secret (Lowkey Vault 7.3.0 behavior) → {@link java.util.Optional#empty()} because
 *       Lowkey Vault returns HTTP 404 for disabled secrets; real Azure Key Vault returns HTTP 403
 *       which throws {@link ConfigPropertySourceException}; sentinel absent from logs</li>
 *   <li>Redaction: no sentinel in captured logs during lookup and 404</li>
 * </ul>
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class AzureKeyVaultPropertySourceIT {

    // --- Vault name and sentinels ---

    /**
     * Vault name registered in the Lowkey Vault container.
     *
     * <p>Lowkey Vault derives the default vault base URL as
     * {@code https://<vaultName>.localhost:<mappedPort>}. The vault name must match the
     * {@code vaultNames} set supplied to the builder.
     */
    private static final String VAULT_NAME = "it";

    /**
     * Docker image for Lowkey Vault (Docker Hub), pinned to the version matching the
     * {@code lowkey-vault-testcontainers:7.3.0} library dependency.
     *
     * <p>The {@code nagyesta/lowkey-vault} image does not publish a {@code latest} tag;
     * the version tag must be supplied explicitly. The {@code -ubi10-minimal} variant is
     * multi-arch (amd64 + arm64), avoiding slow amd64 emulation on arm64 hosts.
     */
    private static final String LOWKEY_IMAGE = "nagyesta/lowkey-vault:7.3.0-ubi10-minimal";

    /** Secret name seeded in the vault for the simple-resolution test. */
    private static final String SECRET_PASSWORD = "password";

    /**
     * Sentinel value seeded under {@value #SECRET_PASSWORD}.
     *
     * <p>The sentinel is unique to this test class; if it appears in a log or exception message,
     * that is a redaction failure.
     */
    private static final String PASSWORD_SENTINEL = "s3cret-LKV-SENTINEL";

    /** Secret name seeded in the vault for the normalization test ({@code '.'} → {@code '-'}). */
    private static final String SECRET_API_KEY = "api-key";

    /**
     * Sentinel value seeded under {@value #SECRET_API_KEY}.
     *
     * <p>Accessed via lookup key {@code "db.api.key"} with prefix {@code "db."}: strip prefix →
     * {@code "api.key"}, normalize {@code '.'} → {@code '-'} → {@code "api-key"}.
     */
    private static final String API_KEY_SENTINEL = "apikey-LKV-SENTINEL2";

    /**
     * Secret name seeded and then disabled in the vault for the disabled-secret test.
     *
     * <p>Lowkey Vault returns a non-200 status when a secret is disabled.
     */
    private static final String SECRET_DISABLED = "disabled-secret";

    /** Sentinel value seeded under {@value #SECRET_DISABLED} before it is disabled. */
    private static final String DISABLED_SENTINEL = "disabled-LKV-SENTINEL3";

    // --- Shared container ---

    /**
     * Static Lowkey Vault container shared across all tests in this class.
     *
     * <p>The 60s Testcontainers default startup timeout is tight under reactor/CI load, so it is
     * raised to 120s (mirrors {@code KafkaTestContainers}).
     */
    @SuppressWarnings("resource")
    static final LowkeyVaultContainer lowkeyVault = LowkeyVaultContainerBuilder.lowkeyVault(LOWKEY_IMAGE)
            .vaultNames(java.util.Set.of(VAULT_NAME))
            .build()
            .withStartupTimeout(Duration.ofSeconds(120));

    // --- Lifecycle ---

    /**
     * Starts the shared Lowkey Vault container and seeds all secrets used by the test suite.
     *
     * <p>The seeding {@link SecretClient} is built via
     * {@code lowkeyVault.getClientFactory().getSecretClientBuilderForDefaultVault()}, which
     * configures TLS trust and dummy basic auth transparently. The same client factory is
     * used in each test to build the gateway's {@link SecretClient}.
     *
     * <p>The {@value #SECRET_DISABLED} secret is seeded and immediately disabled via
     * {@link SecretClient#updateSecretProperties(com.azure.security.keyvault.secrets.models.SecretProperties)}
     * so that test f can assert fail-closed behavior for disabled secrets.
     */
    @BeforeAll
    static void startContainer() {
        lowkeyVault.start();

        SecretClient seedingClient = lowkeyVault
                .getClientFactory()
                .getSecretClientBuilderForDefaultVault()
                .buildClient();

        seedingClient.setSecret(SECRET_PASSWORD, PASSWORD_SENTINEL);
        seedingClient.setSecret(SECRET_API_KEY, API_KEY_SENTINEL);

        // Seed and immediately disable the secret used by test f
        seedingClient.setSecret(SECRET_DISABLED, DISABLED_SENTINEL);
        var props = seedingClient.getSecret(SECRET_DISABLED).getProperties();
        props.setEnabled(false);
        seedingClient.updateSecretProperties(props);
    }

    /**
     * Stops the shared Lowkey Vault container after all tests have completed.
     */
    @AfterAll
    static void stopContainer() {
        lowkeyVault.stop();
    }

    // --- Helpers ---

    /**
     * Builds a real {@link ConfigPropertySource} backed by the Lowkey Vault container via the
     * gateway-factory seam.
     *
     * <p>A {@link SecretClient} is constructed from the container's client factory (trusts the
     * self-signed cert, uses dummy basic auth, calls {@code disableChallengeResourceVerification}).
     * It is injected into {@link SdkKeyVaultGateway} via the package-private test-seam constructor
     * and then wrapped in an {@link AzureKeyVaultPropertySource} via the factory's
     * {@link AzureKeyVaultPropertySourceFactory#AzureKeyVaultPropertySourceFactory(java.util.function.BiFunction)}
     * constructor.
     *
     * <p>The gateway-factory lambda receives the real settings from the factory's schema parser
     * (endpoint, prefix, timeouts) but replaces the standard SDK client build with the
     * pre-configured Lowkey Vault client.
     *
     * @param prefix optional prefix for the source; {@code null} for no prefix
     * @return a {@link ConfigPropertySource} backed by the running Lowkey Vault container
     */
    private static ConfigPropertySource buildSource(String prefix) {
        SecretClient client = lowkeyVault
                .getClientFactory()
                .getSecretClientBuilderForDefaultVault()
                .buildClient();

        AzureKeyVaultPropertySourceFactory factory = new AzureKeyVaultPropertySourceFactory(
                (sourceName, settings) -> new SdkKeyVaultGateway(sourceName, client));

        JsonObject config = new JsonObject().put("endpoint", lowkeyVault.getDefaultVaultBaseUrl());
        if (prefix != null) {
            config.put("prefix", prefix);
        }

        return factory.create("lkv-it", config);
    }

    // --- Tests ---

    /**
     * Seeded secret resolves end-to-end through {@link AzureKeyVaultPropertySource}.
     *
     * <p>Source has prefix {@code "db."}; secret {@value #SECRET_PASSWORD} is stored under that
     * exact normalized name. Lookup key {@code "db.password"} strips prefix to {@code "password"},
     * no dot-substitution needed, validates fine, and the sentinel is returned.
     */
    @Test
    @DisplayName("a: seeded secret resolves end-to-end via prefix + lookup")
    void shouldResolveSeededSecretWithPrefix() {
        ConfigPropertySource source = buildSource("db.");

        assertEquals(
                PASSWORD_SENTINEL,
                source.lookup("db.password").orElse(null),
                "db.password must resolve to the seeded password sentinel");
    }

    /**
     * Normalization: {@code '.'} in stripped key is replaced with {@code '-'} before the SDK call.
     *
     * <p>Lookup key {@code "db.api.key"} with prefix {@code "db."}: strip → {@code "api.key"},
     * normalize {@code '.'} → {@code '-'} → {@code "api-key"}, which is the stored secret name.
     * The sentinel must be returned.
     */
    @Test
    @DisplayName("b: normalization — 'db.api.key' with prefix 'db.' resolves via dot→dash conversion")
    void shouldResolveNormalizedKey() {
        ConfigPropertySource source = buildSource("db.");

        assertEquals(
                API_KEY_SENTINEL,
                source.lookup("db.api.key").orElse(null),
                "db.api.key must resolve via normalization to 'api-key' sentinel");
    }

    /**
     * 404 → not-found: a secret that was never seeded returns {@link java.util.Optional#empty()}.
     *
     * <p>Additionally, the {@link PlaceholderResolver} fallback mechanism is exercised: with
     * {@code "${db.no-such:fallback}"} the engine returns {@code "fallback"}, and
     * {@code "${db.password}"} resolves to the password sentinel, proving the engine layer
     * works with this source.
     */
    @Test
    @DisplayName("c: 404 returns empty; PlaceholderResolver applies fallback for missing key and resolves present key")
    void shouldReturnEmptyForMissingSecretAndApplyFallback() {
        ConfigPropertySource source = buildSource("db.");

        // Direct 404: non-existent secret returns empty
        assertFalse(
                source.lookup("db.no-such").isPresent(),
                "Lookup for a secret that does not exist must return Optional.empty");

        // PlaceholderResolver e2e: missing key uses fallback, present key resolves sentinel
        JsonObject tree = new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("missing", "${db.no-such:fallback}")
                                .put("password", "${db.password}"));

        JsonObject resolved = PlaceholderResolver.resolveTree(tree, List.of(source));

        assertEquals(
                "fallback",
                resolved.getJsonObject("db").getString("missing"),
                "Missing key with default must resolve to the fallback value");
        assertEquals(
                PASSWORD_SENTINEL,
                resolved.getJsonObject("db").getString("password"),
                "Present key must resolve to the password sentinel via PlaceholderResolver");
    }

    /**
     * Error-vs-not-found: a gateway pointed at an unroutable endpoint throws
     * {@link ConfigPropertySourceException} rather than returning empty.
     *
     * <p>Port 1 is the TCP Port Service Multiplexer — connections are refused immediately on all
     * platforms, so the network error surfaces before any HTTP exchange. The source must throw
     * {@link ConfigPropertySourceException} naming the key; neither sentinel must appear in the
     * exception chain (fail-closed and redaction contract).
     *
     * <p>The bad {@link SecretClient} is built with a bare {@link SecretClientBuilder} pointed at
     * an unroutable address. The Lowkey Vault client factory rewrites requests to the actual
     * container authority, so it cannot be used here; instead a plain builder with
     * {@code disableChallengeResourceVerification()} and a dummy
     * {@link BasicAuthenticationCredential} is sufficient — the connection is refused before any
     * TLS or auth exchange takes place.
     */
    @Test
    @DisplayName("d: wrong endpoint throws ConfigPropertySourceException naming the key; sentinels absent")
    void shouldThrowOnUnroutableEndpoint() {
        // Build a SecretClient directly — bypass the Lowkey client factory, which rewrites
        // request authorities to the actual container. Port 1 is reliably connection-refused.
        SecretClient badClient = new SecretClientBuilder()
                .vaultUrl("https://it.localhost:1")
                .credential(new BasicAuthenticationCredential("test", "test"))
                .disableChallengeResourceVerification()
                .buildClient();

        AzureKeyVaultPropertySourceFactory factory = new AzureKeyVaultPropertySourceFactory(
                (sourceName, settings) -> new SdkKeyVaultGateway(sourceName, badClient));

        JsonObject config =
                new JsonObject().put("endpoint", "https://it.localhost:1").put("prefix", "db.");

        ConfigPropertySource source = factory.create("lkv-it-bad", config);

        ConfigPropertySourceException ex = assertThrows(
                ConfigPropertySourceException.class,
                () -> source.lookup("db.password"),
                "Lookup against an unroutable endpoint must throw ConfigPropertySourceException");

        assertTrue(
                containsAnywhere(ex, "password") || containsAnywhere(ex, "db.password"),
                "Exception chain must name the key; was: " + ex.getMessage());
        assertFalse(
                containsAnywhere(ex, PASSWORD_SENTINEL),
                "Exception chain must NOT contain the password sentinel; was: " + ex.getMessage());
        assertFalse(
                containsAnywhere(ex, API_KEY_SENTINEL),
                "Exception chain must NOT contain the api-key sentinel; was: " + ex.getMessage());
    }

    /**
     * Redaction: no sentinel value appears in captured log messages during a successful lookup
     * or a 404 lookup.
     *
     * <p>A {@link ch.qos.logback.core.read.ListAppender} is attached to the root logger for the
     * duration of both lookups. The test then asserts that neither sentinel value appears in any
     * captured log message.
     */
    @Test
    @DisplayName("e: redaction — no sentinel appears in logs during successful lookup and 404")
    void sentinelAbsentFromLogs() {
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.setContext(rootLogger.getLoggerContext());
        appender.start();
        rootLogger.addAppender(appender);

        try {
            ConfigPropertySource source = buildSource("db.");

            // Trigger a successful lookup (value must not leak into logs)
            source.lookup("db.password");

            // Trigger a 404 lookup (empty, no error, but value still must not appear)
            source.lookup("db.no-such");

            String allMessages = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertFalse(
                    allMessages.contains(PASSWORD_SENTINEL),
                    "Password sentinel must NOT appear in any log message; found in: " + allMessages);
            assertFalse(
                    allMessages.contains(API_KEY_SENTINEL),
                    "API key sentinel must NOT appear in any log message; found in: " + allMessages);
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * Disabled secret: Lowkey Vault 7.3.0 returns HTTP 404 for a disabled secret.
     *
     * <p><strong>Lowkey Vault behavior differs from production Azure Key Vault.</strong> A real
     * Azure Key Vault returns HTTP 403 (Forbidden) when a secret exists but is disabled, which
     * causes the source to throw {@link ConfigPropertySourceException} (fail-closed). Lowkey Vault
     * 7.3.0 returns HTTP 404 for disabled secrets, so the source returns
     * {@link java.util.Optional#empty()} rather than an error. This test asserts the actual Lowkey
     * Vault behavior (empty) and confirms that the disabled sentinel value does not appear in any
     * log message (redaction contract still holds).
     *
     * <p>The secret {@value #SECRET_DISABLED} is seeded and disabled in {@link #startContainer()}.
     */
    @Test
    @DisplayName("f: disabled secret in Lowkey Vault → Optional.empty() (Lowkey returns 404; "
            + "real Azure Key Vault returns 403 which would throw)")
    void disabledSecretBehaviorInLowkeyVault() {
        // Capture logs to verify the 404 path and sentinel redaction
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.setContext(rootLogger.getLoggerContext());
        appender.start();
        rootLogger.addAppender(appender);

        try {
            ConfigPropertySource source = buildSource(null);

            // Lowkey Vault 7.3.0 maps disabled secrets to 404 → Optional.empty().
            // Real Azure Key Vault returns 403 → ConfigPropertySourceException (fail-closed).
            assertFalse(
                    source.lookup(SECRET_DISABLED).isPresent(),
                    "Lowkey Vault returns 404 for disabled secrets, which maps to Optional.empty()");

            // Verify the disabled sentinel does not appear in any log message
            String allMessages = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertFalse(
                    allMessages.contains(DISABLED_SENTINEL),
                    "Disabled sentinel must NOT appear in any log message; found in: " + allMessages);
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
