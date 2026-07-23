// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

import static dev.vertique.config.testing.ExceptionChainAssertions.containsAnywhere;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.ManagedIdentityCredential;
import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AzureKeyVaultPropertySourceFactory} using a stub
 * {@link KeyVaultGateway}.
 *
 * <p>All tests operate entirely in-memory with no Azure endpoint. Schema validation,
 * prefix filtering, key normalization, name validation, gateway not-found semantics,
 * gateway error propagation, redaction, ServiceLoader discovery, and credential-builder
 * selection are all verified here.
 */
class AzureKeyVaultPropertySourceFactoryTest {

    // --- Helpers ---

    /**
     * Creates a factory wired to a stub gateway backed by the given secret data
     * (normalizedName → value). A missing key returns {@link Optional#empty()}.
     *
     * @param stubData map from normalized secret name to string value
     * @return configured factory with stub gateway
     */
    private static AzureKeyVaultPropertySourceFactory factoryWithStub(Map<String, String> stubData) {
        return new AzureKeyVaultPropertySourceFactory(
                (name, settings) -> (normalizedName, originalKey) -> Optional.ofNullable(stubData.get(normalizedName)));
    }

    /**
     * Creates a factory whose stub gateway throws a {@link ConfigPropertySourceException} for
     * every lookup.
     *
     * @return configured factory with error-throwing stub gateway
     */
    private static AzureKeyVaultPropertySourceFactory factoryWithErrorGateway() {
        return new AzureKeyVaultPropertySourceFactory((name, settings) -> (normalizedName, originalKey) -> {
            throw new ConfigPropertySourceException(name, originalKey, "simulated vault error");
        });
    }

    /**
     * Builds a minimal valid source config with the given endpoint and no prefix.
     *
     * @param endpoint the vault endpoint URL
     * @return JsonObject source config
     */
    private static JsonObject minimalConfig(String endpoint) {
        return new JsonObject().put("endpoint", endpoint);
    }

    /**
     * Builds a source config with endpoint and prefix.
     *
     * @param endpoint the vault endpoint URL
     * @param prefix   the key prefix
     * @return JsonObject source config
     */
    private static JsonObject configWithPrefix(String endpoint, String prefix) {
        return new JsonObject().put("endpoint", endpoint).put("prefix", prefix);
    }

    // --- ServiceLoader discovery ---

    @Nested
    @DisplayName("ServiceLoader discovery")
    class ServiceLoaderDiscovery {

        @Test
        @DisplayName("type 'azure-keyvault' factory is discovered via ServiceLoader")
        void serviceLoaderFindsAzureKeyVaultFactory() {
            boolean found = false;
            for (ConfigPropertySourceFactory f : ServiceLoader.load(ConfigPropertySourceFactory.class)) {
                if ("azure-keyvault".equals(f.type())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "ServiceLoader must discover ConfigPropertySourceFactory with type 'azure-keyvault'");
        }
    }

    // --- Schema validation ---

    @Nested
    @DisplayName("schema validation")
    class SchemaValidation {

        @Test
        @DisplayName("throws when endpoint is missing")
        void missingEndpointThrows() {
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", new JsonObject()));
            assertTrue(ex.getMessage().contains("endpoint"), "error must mention 'endpoint'");
            assertEquals("test-src", ex.sourceName());
        }

        @Test
        @DisplayName("throws when endpoint is blank")
        void blankEndpointThrows() {
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", new JsonObject().put("endpoint", "   ")));
            assertTrue(ex.getMessage().contains("endpoint"), "error must mention 'endpoint'");
            assertEquals("test-src", ex.sourceName());
        }

        @Test
        @DisplayName("throws when auth method is unknown")
        void unknownAuthMethodThrows() {
            JsonObject config = minimalConfig("https://myvault.vault.azure.net")
                    .put("auth", new JsonObject().put("method", "certificate"));
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("certificate"), "error must name the invalid method");
            assertEquals("test-src", ex.sourceName());
        }

        @Test
        @DisplayName("absent auth block defaults to method 'default'")
        void absentAuthDefaultsToDefault() {
            AzureConnectionSettings[] captured = new AzureConnectionSettings[1];
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return (n, k) -> Optional.empty();
            });

            factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            assertEquals("default", captured[0].authMethod());
        }

        @Test
        @DisplayName("explicit method 'managed-identity' is accepted")
        void managedIdentityMethodAccepted() {
            JsonObject config = minimalConfig("https://myvault.vault.azure.net")
                    .put("auth", new JsonObject().put("method", "managed-identity"));
            AzureConnectionSettings[] captured = new AzureConnectionSettings[1];
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return (n, k) -> Optional.empty();
            });

            assertDoesNotThrow(() -> factory.create("src", config));
            assertEquals("managed-identity", captured[0].authMethod());
        }

        @Test
        @DisplayName("endpoint is passed through to connection settings")
        void endpointPassedThrough() {
            AzureConnectionSettings[] captured = new AzureConnectionSettings[1];
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return (n, k) -> Optional.empty();
            });

            factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            assertEquals("https://myvault.vault.azure.net", captured[0].endpoint());
        }

        @Test
        @DisplayName("default timeouts are 5000 ms")
        void defaultTimeoutsAreFiveSeconds() {
            AzureConnectionSettings[] captured = new AzureConnectionSettings[1];
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return (n, k) -> Optional.empty();
            });

            factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            assertEquals(5000, captured[0].connectTimeoutMs());
            assertEquals(5000, captured[0].readTimeoutMs());
        }

        @Test
        @DisplayName("explicit timeout values are preserved")
        void explicitTimeoutsPreserved() {
            AzureConnectionSettings[] captured = new AzureConnectionSettings[1];
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return (n, k) -> Optional.empty();
            });

            JsonObject config = minimalConfig("https://myvault.vault.azure.net")
                    .put("connectTimeoutMs", 3000)
                    .put("readTimeoutMs", 7000);
            factory.create("src", config);

            assertEquals(3000, captured[0].connectTimeoutMs());
            assertEquals(7000, captured[0].readTimeoutMs());
        }
    }

    // --- Auth settings passthrough ---

    @Nested
    @DisplayName("auth settings passthrough")
    class AuthSettingsPassthrough {

        /**
         * Parses and returns the {@link AzureConnectionSettings} produced by the factory for the
         * given source config. Equivalent to calling {@code factory.create(name, config)} but
         * capturing the settings before they reach the gateway.
         *
         * @param config the source config JSON object
         * @return the parsed {@link AzureConnectionSettings}
         */
        private static AzureConnectionSettings parsedSettings(JsonObject config) {
            AzureConnectionSettings[] captured = new AzureConnectionSettings[1];
            new AzureKeyVaultPropertySourceFactory((name, settings) -> {
                        captured[0] = settings;
                        return (n, k) -> Optional.empty();
                    })
                    .create("src", config);
            return captured[0];
        }

        @Test
        @DisplayName("method='default' without clientId — authMethod is 'default', clientId is null")
        void defaultMethodNoClientId() {
            AzureConnectionSettings settings = parsedSettings(
                    minimalConfig("https://v.vault.azure.net").put("auth", new JsonObject().put("method", "default")));

            assertEquals("default", settings.authMethod());
            assertTrue(settings.clientId() == null, "clientId must be null when absent");
        }

        @Test
        @DisplayName("method='default' with clientId — clientId is passed through")
        void defaultMethodWithClientId() {
            AzureConnectionSettings settings = parsedSettings(minimalConfig("https://v.vault.azure.net")
                    .put("auth", new JsonObject().put("method", "default").put("clientId", "my-client-id")));

            assertEquals("default", settings.authMethod());
            assertEquals("my-client-id", settings.clientId());
        }

        @Test
        @DisplayName("method='managed-identity' without clientId — clientId is null")
        void managedIdentityNoClientId() {
            AzureConnectionSettings settings = parsedSettings(minimalConfig("https://v.vault.azure.net")
                    .put("auth", new JsonObject().put("method", "managed-identity")));

            assertEquals("managed-identity", settings.authMethod());
            assertTrue(settings.clientId() == null, "clientId must be null when absent");
        }

        @Test
        @DisplayName("method='managed-identity' with clientId — clientId is passed through")
        void managedIdentityWithClientId() {
            AzureConnectionSettings settings = parsedSettings(minimalConfig("https://v.vault.azure.net")
                    .put(
                            "auth",
                            new JsonObject().put("method", "managed-identity").put("clientId", "my-mi-client")));

            assertEquals("managed-identity", settings.authMethod());
            assertEquals("my-mi-client", settings.clientId());
        }
    }

    // --- Credential-builder selection ---

    @Nested
    @DisplayName("credential-builder selection")
    class CredentialBuilderSelection {

        @Test
        @DisplayName("method='default' without clientId produces DefaultAzureCredential")
        void defaultMethodProducesDefaultAzureCredential() {
            var credential = AzureCredentials.build("default", null);
            assertInstanceOf(
                    DefaultAzureCredential.class, credential, "method='default' must produce DefaultAzureCredential");
        }

        @Test
        @DisplayName("method='default' with clientId produces DefaultAzureCredential")
        void defaultMethodWithClientIdProducesDefaultAzureCredential() {
            var credential = AzureCredentials.build("default", "my-client-id");
            assertInstanceOf(
                    DefaultAzureCredential.class,
                    credential,
                    "method='default' with clientId must produce DefaultAzureCredential");
        }

        @Test
        @DisplayName("method='managed-identity' without clientId produces ManagedIdentityCredential")
        void managedIdentityMethodProducesManagedIdentityCredential() {
            var credential = AzureCredentials.build("managed-identity", null);
            assertInstanceOf(
                    ManagedIdentityCredential.class,
                    credential,
                    "method='managed-identity' must produce ManagedIdentityCredential");
        }

        @Test
        @DisplayName("method='managed-identity' with clientId produces ManagedIdentityCredential")
        void managedIdentityMethodWithClientIdProducesManagedIdentityCredential() {
            var credential = AzureCredentials.build("managed-identity", "my-client-id");
            assertInstanceOf(
                    ManagedIdentityCredential.class,
                    credential,
                    "method='managed-identity' with clientId must produce ManagedIdentityCredential");
        }
    }

    // --- Timeout validation ---

    @Nested
    @DisplayName("timeout validation")
    class TimeoutValidation {

        @Test
        @DisplayName("zero connectTimeoutMs throws naming the field")
        void zeroConnectTimeoutThrows() {
            JsonObject config = minimalConfig("https://myvault.vault.azure.net").put("connectTimeoutMs", 0);
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("connectTimeoutMs"), "error must name the field");
            assertEquals("test-src", ex.sourceName());
        }

        @Test
        @DisplayName("negative connectTimeoutMs throws naming the field")
        void negativeConnectTimeoutThrows() {
            JsonObject config = minimalConfig("https://myvault.vault.azure.net").put("connectTimeoutMs", -1);
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("connectTimeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("zero readTimeoutMs throws naming the field")
        void zeroReadTimeoutThrows() {
            JsonObject config = minimalConfig("https://myvault.vault.azure.net").put("readTimeoutMs", 0);
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("readTimeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("non-integer connectTimeoutMs throws naming the field")
        void nonIntegerConnectTimeoutThrows() {
            JsonObject config =
                    minimalConfig("https://myvault.vault.azure.net").put("connectTimeoutMs", "not-a-number");
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("connectTimeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("positive connectTimeoutMs and readTimeoutMs are accepted")
        void positiveTimeoutsAccepted() {
            JsonObject config = minimalConfig("https://myvault.vault.azure.net")
                    .put("connectTimeoutMs", 1)
                    .put("readTimeoutMs", 1);
            assertDoesNotThrow(() -> factoryWithStub(Map.of()).create("test-src", config));
        }
    }

    // --- Prefix filter ---

    @Nested
    @DisplayName("prefix filter")
    class PrefixFilter {

        @Test
        @DisplayName("key not starting with prefix returns empty — gateway NOT called")
        void keyNotMatchingPrefixReturnsEmpty() {
            AtomicInteger gatewayCalls = new AtomicInteger(0);
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (n, k) -> {
                gatewayCalls.incrementAndGet();
                return Optional.empty();
            });

            ConfigPropertySource source =
                    factory.create("src", configWithPrefix("https://myvault.vault.azure.net", "db."));

            Optional<String> result = source.lookup("app.setting");

            assertEquals(Optional.empty(), result, "non-matching key must return empty");
            assertEquals(0, gatewayCalls.get(), "gateway must NOT be called for non-matching key");
        }

        @Test
        @DisplayName("key starting with prefix reaches gateway with prefix stripped")
        void keyMatchingPrefixStrippedBeforeGateway() {
            List<String> capturedNames = new ArrayList<>();
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (normalizedName, originalKey) -> {
                capturedNames.add(normalizedName);
                return Optional.of("value");
            });

            ConfigPropertySource source =
                    factory.create("src", configWithPrefix("https://myvault.vault.azure.net", "db."));

            source.lookup("db.password");

            assertEquals(1, capturedNames.size(), "gateway must be called exactly once");
            assertEquals("password", capturedNames.get(0), "prefix must be stripped before gateway call");
        }

        @Test
        @DisplayName("no prefix — all keys reach gateway")
        void noPrefixAllKeysReachGateway() {
            AtomicInteger gatewayCalls = new AtomicInteger(0);
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (n, k) -> {
                gatewayCalls.incrementAndGet();
                return Optional.of("value");
            });

            ConfigPropertySource source = factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            source.lookup("some-key");

            assertEquals(1, gatewayCalls.get(), "gateway must be called when no prefix configured");
        }
    }

    // --- Key normalization ---

    @Nested
    @DisplayName("key normalization")
    class KeyNormalization {

        @Test
        @DisplayName("'.' in key is replaced with '-' before gateway call")
        void dotReplacedWithDash() {
            List<String> capturedNames = new ArrayList<>();
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (normalizedName, originalKey) -> {
                capturedNames.add(normalizedName);
                return Optional.of("value");
            });

            ConfigPropertySource source = factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            source.lookup("db.password");

            assertEquals(1, capturedNames.size());
            assertEquals("db-password", capturedNames.get(0), "'.' must be normalized to '-'");
        }

        @Test
        @DisplayName("key with no '.' is passed through unchanged")
        void keyWithoutDotUnchanged() {
            List<String> capturedNames = new ArrayList<>();
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (normalizedName, originalKey) -> {
                capturedNames.add(normalizedName);
                return Optional.of("value");
            });

            ConfigPropertySource source = factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            source.lookup("my-secret");

            assertEquals("my-secret", capturedNames.get(0), "key without '.' must pass through unchanged");
        }
    }

    // --- Invalid-after-normalization ---

    @Nested
    @DisplayName("invalid-after-normalization — no gateway call")
    class InvalidAfterNormalization {

        @Test
        @DisplayName("key with underscore returns empty — gateway NOT called")
        void underscoreKeyReturnsEmpty() {
            AtomicInteger gatewayCalls = new AtomicInteger(0);
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (n, k) -> {
                gatewayCalls.incrementAndGet();
                return Optional.empty();
            });

            ConfigPropertySource source = factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            Optional<String> result = source.lookup("DB_PASSWORD");

            assertEquals(Optional.empty(), result);
            assertEquals(0, gatewayCalls.get(), "gateway must NOT be called for key with underscore");
        }

        @Test
        @DisplayName("key >127 chars returns empty — gateway NOT called")
        void keyOver127CharsReturnsEmpty() {
            AtomicInteger gatewayCalls = new AtomicInteger(0);
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (n, k) -> {
                gatewayCalls.incrementAndGet();
                return Optional.empty();
            });

            ConfigPropertySource source = factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            // 128 valid chars should fail validation (max is 127)
            String longKey = "a".repeat(128);
            Optional<String> result = source.lookup(longKey);

            assertEquals(Optional.empty(), result);
            assertEquals(0, gatewayCalls.get(), "gateway must NOT be called for key >127 chars");
        }

        @Test
        @DisplayName("exactly 127 valid chars is accepted")
        void exactly127CharsAccepted() {
            AtomicInteger gatewayCalls = new AtomicInteger(0);
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (n, k) -> {
                gatewayCalls.incrementAndGet();
                return Optional.of("value");
            });

            ConfigPropertySource source = factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            String validKey = "a".repeat(127);
            Optional<String> result = source.lookup(validKey);

            assertEquals(Optional.of("value"), result);
            assertEquals(1, gatewayCalls.get(), "gateway must be called for 127-char key");
        }

        @Test
        @DisplayName("empty key after prefix strip returns empty — gateway NOT called")
        void emptyKeyAfterPrefixStripReturnsEmpty() {
            AtomicInteger gatewayCalls = new AtomicInteger(0);
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (n, k) -> {
                gatewayCalls.incrementAndGet();
                return Optional.empty();
            });

            ConfigPropertySource source =
                    factory.create("src", configWithPrefix("https://myvault.vault.azure.net", "db."));

            // key == prefix → stripped to "" → empty string is invalid
            Optional<String> result = source.lookup("db.");

            assertEquals(Optional.empty(), result);
            assertEquals(0, gatewayCalls.get(), "gateway must NOT be called for empty normalized name");
        }
    }

    // --- Lookup contract ---

    @Nested
    @DisplayName("lookup contract")
    class LookupContract {

        @Test
        @DisplayName("gateway hit returns value")
        void gatewayHitReturnsValue() {
            ConfigPropertySource source = factoryWithStub(Map.of("my-key", "secret-value"))
                    .create("src", minimalConfig("https://myvault.vault.azure.net"));

            assertEquals(Optional.of("secret-value"), source.lookup("my-key"));
        }

        @Test
        @DisplayName("gateway 404 (Optional.empty) propagates as empty")
        void gateway404ReturnsEmpty() {
            ConfigPropertySource source =
                    factoryWithStub(Map.of()).create("src", minimalConfig("https://myvault.vault.azure.net"));

            assertEquals(Optional.empty(), source.lookup("absent-key"));
        }

        @Test
        @DisplayName("gateway error propagates as ConfigPropertySourceException naming source and key")
        void gatewayErrorPropagatesCorrectly() {
            ConfigPropertySource source = factoryWithErrorGateway()
                    .create("my-vault-source", minimalConfig("https://myvault.vault.azure.net"));

            var ex = assertThrows(ConfigPropertySourceException.class, () -> source.lookup("my-key"));

            assertEquals("my-vault-source", ex.sourceName(), "sourceName must match declared source name");
            assertEquals("my-key", ex.key(), "key must match the looked-up placeholder key");
        }

        @Test
        @DisplayName("source name is returned correctly")
        void sourceNameReturned() {
            ConfigPropertySource source = factoryWithStub(Map.of())
                    .create("my-vault-source", minimalConfig("https://myvault.vault.azure.net"));

            assertEquals("my-vault-source", source.name());
        }
    }

    // --- Sentinel chain-walk assertion ---

    @Nested
    @DisplayName("redaction — no sentinel value in exception chain or log output")
    class RedactionTest {

        @Test
        @DisplayName("exception chain must NOT contain the sentinel secret value on gateway error")
        void exceptionChainDoesNotContainSentinelValue() {
            String sentinel = "AZURE-SECRET-SENTINEL-9821";

            // Gateway throws after successfully returning a first key; the exception chain
            // must not expose the first secret's value even if it was previously accessed.
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> (normalizedName, originalKey) -> {
                if ("first-key".equals(normalizedName)) {
                    return Optional.of(sentinel); // the sentinel value was "resolved" for another key
                }
                // second key throws — exception chain must not expose the sentinel
                throw new ConfigPropertySourceException(
                        name, originalKey, "failed to fetch secret 'second-key': HTTP 403");
            });

            ConfigPropertySource source = factory.create("src", minimalConfig("https://myvault.vault.azure.net"));

            // First lookup succeeds (sentinel value is in the resolved map, not in exceptions)
            assertEquals(Optional.of(sentinel), source.lookup("first-key"));

            // Second lookup throws — exception chain must not contain the sentinel
            var ex = assertThrows(ConfigPropertySourceException.class, () -> source.lookup("second-key"));

            assertFalse(
                    containsAnywhere(ex, sentinel),
                    "exception chain must NOT contain sentinel secret value; was: " + ex.getMessage());
            assertEquals("src", ex.sourceName());
        }

        @Test
        @DisplayName("sentinel secret value never appears in captured log messages on successful lookup")
        void sentinelAbsentFromLogs() {
            String sentinel = "AZURE-LOG-SENTINEL-4412";

            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.setContext(rootLogger.getLoggerContext());
            appender.start();
            rootLogger.addAppender(appender);

            try {
                ConfigPropertySource source = factoryWithStub(Map.of("my-secret", sentinel))
                        .create("azure-redaction-test", minimalConfig("https://myvault.vault.azure.net"));

                // Trigger a lookup to access the value
                Optional<String> result = source.lookup("my-secret");
                assertTrue(result.isPresent(), "lookup must succeed");

                String allMessages = appender.list.stream()
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .collect(java.util.stream.Collectors.joining("\n"));

                assertFalse(
                        allMessages.contains(sentinel),
                        "sentinel secret value must NOT appear in any log message; found in: " + allMessages);
            } finally {
                rootLogger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    // --- Endpoint URI validation (item 5) ---

    @Nested
    @DisplayName("endpoint URI validation")
    class EndpointUriValidation {

        @Test
        @DisplayName("relative URI (no scheme) is rejected")
        void relativeUriRejected() {
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("src", new JsonObject().put("endpoint", "myvault.vault.azure.net")));
            assertTrue(ex.getMessage().toLowerCase().contains("endpoint"), "error must mention 'endpoint'");
        }

        @Test
        @DisplayName("URI with userinfo (credentials) is rejected")
        void userinfoRejected() {
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("src", new JsonObject().put("endpoint", "https://user:pass@myvault.vault.azure.net")));
            assertTrue(ex.getMessage().toLowerCase().contains("endpoint"), "error must mention 'endpoint'");
        }

        @Test
        @DisplayName("URI with query string is rejected")
        void queryStringRejected() {
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("src", new JsonObject().put("endpoint", "https://myvault.vault.azure.net?foo=bar")));
            assertTrue(ex.getMessage().toLowerCase().contains("endpoint"), "error must mention 'endpoint'");
        }

        @Test
        @DisplayName("URI with fragment is rejected")
        void fragmentRejected() {
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("src", new JsonObject().put("endpoint", "https://myvault.vault.azure.net#section")));
            assertTrue(ex.getMessage().toLowerCase().contains("endpoint"), "error must mention 'endpoint'");
        }

        @Test
        @DisplayName("http on non-localhost production host is rejected")
        void httpOnProductionHostRejected() {
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("src", new JsonObject().put("endpoint", "http://myvault.vault.azure.net")));
            assertTrue(ex.getMessage().toLowerCase().contains("endpoint"), "error must mention 'endpoint'");
        }

        @Test
        @DisplayName("http on localhost is accepted (test-double exemption)")
        void httpOnLocalhostAccepted() {
            assertDoesNotThrow(() ->
                    factoryWithStub(Map.of()).create("src", new JsonObject().put("endpoint", "http://localhost:8443")));
        }

        @Test
        @DisplayName("http on 127.0.0.1 is accepted (test-double exemption)")
        void httpOn127Accepted() {
            assertDoesNotThrow(() ->
                    factoryWithStub(Map.of()).create("src", new JsonObject().put("endpoint", "http://127.0.0.1:8443")));
        }

        @Test
        @DisplayName("http on [::1] (IPv6 loopback) is accepted (test-double exemption)")
        void httpOnIpv6LoopbackAccepted() {
            assertDoesNotThrow(() ->
                    factoryWithStub(Map.of()).create("src", new JsonObject().put("endpoint", "http://[::1]:8443")));
        }

        @Test
        @DisplayName("http on LOCALHOST (uppercase) is accepted (case-insensitive allowlist)")
        void httpOnUppercaseLocalhostAccepted() {
            assertDoesNotThrow(() ->
                    factoryWithStub(Map.of()).create("src", new JsonObject().put("endpoint", "http://LOCALHOST:8443")));
        }

        @Test
        @DisplayName("canonical form for IPv6 loopback preserves bracketed host: scheme://[::1]:port")
        void canonicalFormForIpv6LoopbackPreservesBrackets() {
            AzureConnectionSettings[] captured = new AzureConnectionSettings[1];
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return (n, k) -> Optional.empty();
            });

            assertDoesNotThrow(() -> factory.create("src", new JsonObject().put("endpoint", "http://[::1]:8443")));

            assertEquals(
                    "http://[::1]:8443",
                    captured[0].canonicalEndpoint(),
                    "canonical endpoint for IPv6 loopback must preserve bracket notation");
        }

        @Test
        @DisplayName("https endpoint is accepted")
        void httpsAccepted() {
            assertDoesNotThrow(() -> factoryWithStub(Map.of())
                    .create("src", new JsonObject().put("endpoint", "https://myvault.vault.azure.net")));
        }

        @Test
        @DisplayName("URI with unsupported scheme (ftp) is rejected")
        void unsupportedSchemeRejected() {
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("src", new JsonObject().put("endpoint", "ftp://myvault.vault.azure.net")));
            assertTrue(ex.getMessage().toLowerCase().contains("endpoint"), "error must mention 'endpoint'");
        }

        @Test
        @DisplayName("endpoint with path component is rejected with 'must not contain a path'")
        void endpointWithPathRejected() {
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("src", new JsonObject().put("endpoint", "https://myvault.vault.azure.net/foo")));
            assertTrue(ex.getMessage().toLowerCase().contains("path"), "error must mention 'path'");
        }

        @Test
        @DisplayName("endpoint with trailing '/' is accepted and canonicalized to scheme://host")
        void trailingSlashAccepted() {
            AzureConnectionSettings[] captured = new AzureConnectionSettings[1];
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return (n, k) -> Optional.empty();
            });

            assertDoesNotThrow(
                    () -> factory.create("src", new JsonObject().put("endpoint", "https://myvault.vault.azure.net/")));

            assertEquals(
                    "https://myvault.vault.azure.net",
                    captured[0].canonicalEndpoint(),
                    "trailing '/' must be stripped in canonical form");
        }

        @Test
        @DisplayName("SDK-bound endpoint (canonicalEndpoint) equals scheme://host — never contains the path")
        void sdkBoundEndpointIsCanonical() {
            AzureConnectionSettings[] captured = new AzureConnectionSettings[1];
            var factory = new AzureKeyVaultPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return (n, k) -> Optional.empty();
            });

            // trailing slash is accepted; SDK must receive canonical form
            factory.create("src", new JsonObject().put("endpoint", "https://myvault.vault.azure.net/"));

            assertFalse(
                    captured[0].canonicalEndpoint().endsWith("/"),
                    "canonical endpoint must not end with '/'; got: " + captured[0].canonicalEndpoint());
            assertEquals(
                    "https://myvault.vault.azure.net",
                    captured[0].canonicalEndpoint(),
                    "canonical endpoint must be scheme://host only");
        }

        @Test
        @DisplayName("INFO log at construction logs only canonical scheme://host[:port]")
        void infoLogContainsOnlyCanonicalForm() {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(AzureKeyVaultPropertySource.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.setContext(logger.getLoggerContext());
            appender.start();
            logger.addAppender(appender);
            try {
                // Canonical endpoint (no path): canonical form must appear in the log.
                // Endpoints with a path are rejected at create time (see endpointWithPathRejected).
                factoryWithStub(Map.of())
                        .create("src", new JsonObject().put("endpoint", "https://myvault.vault.azure.net"));
                String logs = appender.list.stream()
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .collect(java.util.stream.Collectors.joining("\n"));
                assertTrue(
                        logs.contains("https://myvault.vault.azure.net"),
                        "canonical endpoint must appear in log; got: " + logs);
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    // --- Close contract ---

    @Nested
    @DisplayName("close contract")
    class CloseContract {

        @Test
        @DisplayName("close() does not throw")
        void closeDoesNotThrow() {
            ConfigPropertySource source =
                    factoryWithStub(Map.of()).create("src", minimalConfig("https://myvault.vault.azure.net"));
            assertNotNull(source);
            assertDoesNotThrow(source::close);
        }
    }
}
