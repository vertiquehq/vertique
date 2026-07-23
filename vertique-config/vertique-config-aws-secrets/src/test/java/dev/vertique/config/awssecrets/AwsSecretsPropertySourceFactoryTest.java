// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

import static dev.vertique.config.testing.ExceptionChainAssertions.containsAnywhere;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AwsSecretsPropertySourceFactory} using a stub {@link SecretsGateway}.
 *
 * <p>All tests operate entirely in-memory with no AWS endpoint. Schema validation,
 * eager-load semantics, JSON-blob flattening, plain-string key mode, redaction,
 * ServiceLoader discovery, and close-contract are all verified here.
 */
class AwsSecretsPropertySourceFactoryTest {

    // --- Helpers ---

    /**
     * Creates a factory wired to the given stub gateway data (secretId → raw SecretString).
     *
     * @param stubData map from secretId to the string value the gateway returns
     * @return configured factory with stub gateway
     */
    private static AwsSecretsPropertySourceFactory factoryWithStub(Map<String, String> stubData) {
        return new AwsSecretsPropertySourceFactory((name, settings) -> secretId -> {
            String value = stubData.get(secretId);
            if (value == null) {
                throw new ConfigPropertySourceException(name, "secret '" + secretId + "' not found in stub");
            }
            return value;
        });
    }

    /**
     * Builds a minimal valid source config with a single plain-string secret entry.
     *
     * @param secretId the secret ID to use
     * @param key      the key under which to expose the secret
     * @return JsonObject source config
     */
    private static JsonObject minimalKeyConfig(String secretId, String key) {
        return new JsonObject()
                .put(
                        "secrets",
                        new JsonArray()
                                .add(new JsonObject().put("secretId", secretId).put("key", key)));
    }

    /**
     * Builds a minimal valid source config with a single JSON-blob prefix entry.
     *
     * @param secretId the secret ID to use
     * @param prefix   the prefix to apply
     * @return JsonObject source config
     */
    private static JsonObject minimalPrefixConfig(String secretId, String prefix) {
        return new JsonObject()
                .put(
                        "secrets",
                        new JsonArray()
                                .add(new JsonObject().put("secretId", secretId).put("prefix", prefix)));
    }

    // --- ServiceLoader discovery ---

    @Nested
    @DisplayName("ServiceLoader discovery")
    class ServiceLoaderDiscovery {

        @Test
        @DisplayName("type 'aws-secrets' factory is discovered via ServiceLoader")
        void serviceLoaderFindsAwsSecretsFactory() {
            boolean found = false;
            for (ConfigPropertySourceFactory f : ServiceLoader.load(ConfigPropertySourceFactory.class)) {
                if ("aws-secrets".equals(f.type())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "ServiceLoader must discover ConfigPropertySourceFactory with type 'aws-secrets'");
        }
    }

    // --- Schema validation ---

    @Nested
    @DisplayName("schema validation")
    class SchemaValidation {

        @Test
        @DisplayName("throws when secrets array is missing")
        void missingSecretsThrows() {
            JsonObject config = new JsonObject();
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("secrets"), "error must mention 'secrets'");
            assertEquals("test-src", ex.sourceName());
        }

        @Test
        @DisplayName("throws when secrets array is empty")
        void emptySecretsThrows() {
            JsonObject config = new JsonObject().put("secrets", new JsonArray());
            assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
        }

        @Test
        @DisplayName("throws when secretId is missing from entry")
        void missingSecretIdThrows() {
            JsonObject config =
                    new JsonObject().put("secrets", new JsonArray().add(new JsonObject().put("key", "mykey")));
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("secretId"), "error must mention 'secretId'");
        }

        @Test
        @DisplayName("throws when secretId is blank")
        void blankSecretIdThrows() {
            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject().put("secretId", "  ").put("key", "mykey")));
            assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
        }

        @Test
        @DisplayName("throws when entry has both prefix and key")
        void bothPrefixAndKeyThrows() {
            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("secretId", "my/secret")
                                            .put("prefix", "db.")
                                            .put("key", "my.key")));
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(
                    ex.getMessage().contains("exactly one") || ex.getMessage().contains("not both"),
                    "error must indicate both is invalid");
        }

        @Test
        @DisplayName("throws when entry has neither prefix nor key")
        void neitherPrefixNorKeyThrows() {
            JsonObject config =
                    new JsonObject().put("secrets", new JsonArray().add(new JsonObject().put("secretId", "my/secret")));
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(
                    ex.getMessage().contains("exactly one"),
                    "error must indicate exactly one of prefix/key is required");
        }
    }

    // --- Connection settings passthrough ---

    @Nested
    @DisplayName("connection settings passthrough")
    class ConnectionSettings {

        @Test
        @DisplayName("region is passed through to connection settings")
        void regionPassedThrough() {
            AwsConnectionSettings[] captured = new AwsConnectionSettings[1];
            AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return secretId -> "value";
            });

            JsonObject config = minimalKeyConfig("my/secret", "my.key").put("region", "eu-west-1");
            factory.create("src", config);

            assertEquals("eu-west-1", captured[0].region());
        }

        @Test
        @DisplayName("absent region produces null (SDK default chain)")
        void absentRegionIsNull() {
            AwsConnectionSettings[] captured = new AwsConnectionSettings[1];
            AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return secretId -> "value";
            });

            factory.create("src", minimalKeyConfig("my/secret", "my.key"));

            assertTrue(captured[0].region() == null, "absent region must be null (SDK resolves from default chain)");
        }

        @Test
        @DisplayName("endpointOverride is passed through to connection settings")
        void endpointOverridePassedThrough() {
            AwsConnectionSettings[] captured = new AwsConnectionSettings[1];
            AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return secretId -> "value";
            });

            JsonObject config =
                    minimalKeyConfig("my/secret", "my.key").put("endpointOverride", "http://localhost:4566");
            factory.create("src", config);

            assertEquals("http://localhost:4566", captured[0].endpointOverride());
        }

        @Test
        @DisplayName("default timeouts are 5000 ms")
        void defaultTimeoutsAreFiveSeconds() {
            AwsConnectionSettings[] captured = new AwsConnectionSettings[1];
            AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return secretId -> "value";
            });

            factory.create("src", minimalKeyConfig("my/secret", "my.key"));

            assertEquals(5000, captured[0].connectTimeoutMs());
            assertEquals(5000, captured[0].readTimeoutMs());
        }

        @Test
        @DisplayName("explicit timeout values are preserved")
        void explicitTimeoutsPreserved() {
            AwsConnectionSettings[] captured = new AwsConnectionSettings[1];
            AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory((name, settings) -> {
                captured[0] = settings;
                return secretId -> "value";
            });

            JsonObject config = minimalKeyConfig("my/secret", "my.key")
                    .put("connectTimeoutMs", 3000)
                    .put("readTimeoutMs", 7000);
            factory.create("src", config);

            assertEquals(3000, captured[0].connectTimeoutMs());
            assertEquals(7000, captured[0].readTimeoutMs());
        }
    }

    // --- Eager read at create ---

    @Nested
    @DisplayName("eager read at create")
    class EagerRead {

        @Test
        @DisplayName("gateway is called for each declared secret at create time")
        void allSecretsReadAtCreate() {
            List<String> fetchedIds = new ArrayList<>();
            AwsSecretsPropertySourceFactory factory =
                    new AwsSecretsPropertySourceFactory((name, settings) -> secretId -> {
                        fetchedIds.add(secretId);
                        return "value";
                    });

            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("secretId", "secret/first")
                                            .put("key", "k1"))
                                    .add(new JsonObject()
                                            .put("secretId", "secret/second")
                                            .put("key", "k2")));

            factory.create("src", config);

            assertEquals(2, fetchedIds.size());
            assertTrue(fetchedIds.contains("secret/first"));
            assertTrue(fetchedIds.contains("secret/second"));
        }

        @Test
        @DisplayName("secrets are fetched in declaration order")
        void secretsFetchedInDeclarationOrder() {
            List<String> fetchOrder = new ArrayList<>();
            AwsSecretsPropertySourceFactory factory =
                    new AwsSecretsPropertySourceFactory((name, settings) -> secretId -> {
                        fetchOrder.add(secretId);
                        return "value";
                    });

            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject().put("secretId", "a").put("key", "ka"))
                                    .add(new JsonObject().put("secretId", "b").put("key", "kb"))
                                    .add(new JsonObject().put("secretId", "c").put("key", "kc")));

            factory.create("src", config);

            assertEquals(List.of("a", "b", "c"), fetchOrder);
        }

        @Test
        @DisplayName("gateway throw at create wraps as ConfigPropertySourceException with source name")
        void gatewayThrowWrapsCorrectly() {
            AwsSecretsPropertySourceFactory factory =
                    new AwsSecretsPropertySourceFactory((name, settings) -> secretId -> {
                        throw new ConfigPropertySourceException(name, "secret '" + secretId + "' not found");
                    });

            JsonObject config = minimalKeyConfig("my/secret", "my.key");
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("failing-source", config));

            assertNotNull(ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("my/secret") || ex.getMessage().contains("not found"),
                    "exception message must describe the failure");
            assertEquals("failing-source", ex.sourceName(), "sourceName must equal the declared source name");
        }

        @Test
        @DisplayName("gateway throw message must NOT contain the sentinel secret value")
        void gatewayThrowMessageDoesNotContainSecretValue() {
            // secret A serves the sentinel value; secret B throws — the exception chain
            // must not expose A's value even though the gateway had already fetched it.
            String sentinel = "SUPER-SECRET-SENTINEL-AWS-7734";

            AwsSecretsPropertySourceFactory factory =
                    new AwsSecretsPropertySourceFactory((name, settings) -> secretId -> {
                        if ("my/secret-a".equals(secretId)) {
                            return sentinel;
                        }
                        // secret B throws after secret A was already fetched
                        throw new ConfigPropertySourceException(
                                name, "failed to fetch secret '" + secretId + "': AccessDenied");
                    });

            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("secretId", "my/secret-a")
                                            .put("key", "k1"))
                                    .add(new JsonObject()
                                            .put("secretId", "my/secret-b")
                                            .put("key", "k2")));

            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));

            // Walk the entire exception chain (message + causes) and assert sentinel is absent
            assertFalse(
                    containsAnywhere(ex, sentinel),
                    "exception chain must NOT contain sentinel secret value; was: " + ex.getMessage());

            // The exception must name the source it came from
            assertEquals("src", ex.sourceName());
        }
    }

    // --- JSON-blob (prefix mode) ---

    @Nested
    @DisplayName("JSON-blob prefix mode")
    class JsonBlobPrefixMode {

        @Test
        @DisplayName("JSON-blob secret is flattened under prefix")
        void jsonBlobFlattened() {
            String json =
                    new JsonObject().put("host", "pg-host").put("port", "5432").encode();
            ConfigPropertySource source =
                    factoryWithStub(Map.of("my/db", json)).create("src", minimalPrefixConfig("my/db", "db."));

            assertEquals(Optional.of("pg-host"), source.lookup("db.host"));
            assertEquals(Optional.of("5432"), source.lookup("db.port"));
        }

        @Test
        @DisplayName("nested JSON object in blob is dot-joined under prefix")
        void nestedJsonBlobDotJoined() {
            String json = new JsonObject()
                    .put("db", new JsonObject().put("host", "pg-host"))
                    .encode();
            ConfigPropertySource source =
                    factoryWithStub(Map.of("my/app", json)).create("src", minimalPrefixConfig("my/app", "app."));

            assertEquals(Optional.of("pg-host"), source.lookup("app.db.host"));
        }

        @Test
        @DisplayName("non-JSON SecretString with prefix mode throws; message names secretId not content")
        void nonJsonSecretStringWithPrefixThrows() {
            // Pure-alphanumeric shape: Jackson's "Unrecognized token" message embeds the leading
            // identifier-char run of the parsed input — the whole secret for this common shape.
            String alnumSecret = "ghpSENTINEL9Token4Xyz";
            var ex = assertThrows(
                    ConfigPropertySourceException.class, () -> factoryWithStub(Map.of("my/secret", alnumSecret))
                            .create("src", minimalPrefixConfig("my/secret", "pfx.")));

            assertTrue(ex.getMessage().contains("my/secret"), "error must name the secretId");
            assertFalse(
                    containsAnywhere(ex, alnumSecret),
                    "exception chain must NOT contain the secret content; was: " + ex.getMessage());
        }

        @Test
        @DisplayName("JSON string-scalar secret with prefix fails closed without echoing the value")
        void jsonStringScalarWithPrefixThrowsWithoutEcho() {
            // Valid JSON but not an object: Jackson's MismatchedInputException embeds the FULL
            // string value verbatim ("from String value ('...')") — must never reach the chain.
            String scalarSecret = "\"s3cr3t-token-SENTINEL-77\"";
            var ex = assertThrows(
                    ConfigPropertySourceException.class, () -> factoryWithStub(Map.of("my/secret", scalarSecret))
                            .create("src", minimalPrefixConfig("my/secret", "pfx.")));

            assertTrue(ex.getMessage().contains("my/secret"), "error must name the secretId");
            assertFalse(
                    containsAnywhere(ex, "SENTINEL-77"),
                    "exception chain must NOT contain the secret content; was: " + ex.getMessage());
        }

        @Test
        @DisplayName("empty prefix is allowed for JSON-blob mode")
        void emptyPrefixAllowed() {
            String json = new JsonObject().put("mykey", "myvalue").encode();
            ConfigPropertySource source =
                    factoryWithStub(Map.of("my/secret", json)).create("src", minimalPrefixConfig("my/secret", ""));

            assertEquals(Optional.of("myvalue"), source.lookup("mykey"));
        }
    }

    // --- Plain-string (key mode) ---

    @Nested
    @DisplayName("plain-string key mode")
    class PlainStringKeyMode {

        @Test
        @DisplayName("plain-string secret is exposed under declared key")
        void plainStringUnderKey() {
            ConfigPropertySource source = factoryWithStub(Map.of("my/token", "tok3n-value"))
                    .create("src", minimalKeyConfig("my/token", "api.token"));

            assertEquals(Optional.of("tok3n-value"), source.lookup("api.token"));
        }

        @Test
        @DisplayName("JSON-looking string in key mode is stored as-is, not parsed")
        void jsonStringInKeyModeNotParsed() {
            String jsonLooking = "{\"host\":\"localhost\"}";
            ConfigPropertySource source = factoryWithStub(Map.of("my/json", jsonLooking))
                    .create("src", minimalKeyConfig("my/json", "raw.value"));

            assertEquals(Optional.of(jsonLooking), source.lookup("raw.value"));
            // sub-keys from JSON must not appear
            assertEquals(Optional.empty(), source.lookup("raw.value.host"));
        }

        @Test
        @DisplayName("plain-string secret returns empty optional for different key")
        void lookupDifferentKeyReturnsEmpty() {
            ConfigPropertySource source = factoryWithStub(Map.of("my/token", "tok3n"))
                    .create("src", minimalKeyConfig("my/token", "api.token"));

            assertEquals(Optional.empty(), source.lookup("some.other.key"));
        }
    }

    // --- Lookup contract ---

    @Nested
    @DisplayName("lookup contract")
    class LookupContract {

        @Test
        @DisplayName("lookup returns empty for missing key")
        void lookupMissingKeyReturnsEmpty() {
            ConfigPropertySource source = factoryWithStub(Map.of("my/secret", "value"))
                    .create("src", minimalKeyConfig("my/secret", "my.key"));

            assertEquals(Optional.empty(), source.lookup("no-such-key"));
        }

        @Test
        @DisplayName("lookup returns value for present key")
        void lookupPresentKeyReturnsValue() {
            ConfigPropertySource source = factoryWithStub(Map.of("my/secret", "myvalue"))
                    .create("src", minimalKeyConfig("my/secret", "my.key"));

            assertEquals(Optional.of("myvalue"), source.lookup("my.key"));
        }

        @Test
        @DisplayName("source name is returned correctly")
        void sourceNameReturned() {
            ConfigPropertySource source = factoryWithStub(Map.of("my/secret", "value"))
                    .create("my-aws-source", minimalKeyConfig("my/secret", "k"));

            assertEquals("my-aws-source", source.name());
        }
    }

    // --- SecretEntry record ---

    @Nested
    @DisplayName("SecretEntry record")
    class SecretEntryRecord {

        @Test
        @DisplayName("isPrefixMode returns true when prefix is non-null")
        void isPrefixModeTrue() {
            SecretEntry entry = new SecretEntry("id", "pfx.", null);
            assertTrue(entry.isPrefixMode());
        }

        @Test
        @DisplayName("isPrefixMode returns false when key is non-null")
        void isPrefixModeFalse() {
            SecretEntry entry = new SecretEntry("id", null, "mykey");
            assertFalse(entry.isPrefixMode());
        }
    }

    // --- Timeout validation ---

    @Nested
    @DisplayName("timeout validation")
    class TimeoutValidation {

        @Test
        @DisplayName("zero connectTimeoutMs throws naming the field")
        void zeroConnectTimeoutThrows() {
            JsonObject config = minimalKeyConfig("my/secret", "my.key").put("connectTimeoutMs", 0);
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("connectTimeoutMs"), "error must name the field");
            assertEquals("test-src", ex.sourceName());
        }

        @Test
        @DisplayName("negative connectTimeoutMs throws naming the field")
        void negativeConnectTimeoutThrows() {
            JsonObject config = minimalKeyConfig("my/secret", "my.key").put("connectTimeoutMs", -1);
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("connectTimeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("zero readTimeoutMs throws naming the field")
        void zeroReadTimeoutThrows() {
            JsonObject config = minimalKeyConfig("my/secret", "my.key").put("readTimeoutMs", 0);
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("readTimeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("non-integer connectTimeoutMs throws naming the field")
        void nonIntegerConnectTimeoutThrows() {
            JsonObject config = minimalKeyConfig("my/secret", "my.key").put("connectTimeoutMs", "not-a-number");
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("connectTimeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("positive connectTimeoutMs and readTimeoutMs are accepted")
        void positiveTimeoutsAccepted() {
            JsonObject config = minimalKeyConfig("my/secret", "my.key")
                    .put("connectTimeoutMs", 1)
                    .put("readTimeoutMs", 1);
            assertDoesNotThrow(
                    () -> factoryWithStub(Map.of("my/secret", "value")).create("test-src", config));
        }
    }

    // --- Blank key validation ---

    @Nested
    @DisplayName("blank key validation")
    class BlankKeyValidation {

        @Test
        @DisplayName("blank 'key' field throws naming index and secretId")
        void blankKeyThrows() {
            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("secretId", "my/secret")
                                            .put("key", "   ")));
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of())
                    .create("test-src", config));
            assertTrue(ex.getMessage().contains("key"), "error must mention 'key'");
            assertTrue(ex.getMessage().contains("my/secret"), "error must name the secretId");
            assertEquals("test-src", ex.sourceName());
        }
    }

    // --- Redaction log test ---

    @Nested
    @DisplayName("redaction — no sentinel value in log output")
    class RedactionTest {

        @Test
        @DisplayName("sentinel secret value never appears in captured log messages on successful create+lookup")
        void sentinelAbsentFromLogs() {
            String sentinel = "AWS-SECRET-SENTINEL-7391";

            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.setContext(rootLogger.getLoggerContext());
            appender.start();
            rootLogger.addAppender(appender);

            try {
                // Store sentinel as a plain-string secret
                ConfigPropertySource source = factoryWithStub(Map.of("my/token", sentinel))
                        .create("aws-redaction-test", minimalKeyConfig("my/token", "token.key"));

                // Trigger a lookup to ensure the value is accessed
                Optional<String> result = source.lookup("token.key");
                assertTrue(result.isPresent());

                // Verify no log line contains the sentinel
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

    // --- Close is a no-op ---

    @Nested
    @DisplayName("close contract")
    class CloseContract {

        @Test
        @DisplayName("close() does not throw")
        void closeDoesNotThrow() {
            ConfigPropertySource source =
                    factoryWithStub(Map.of("my/secret", "value")).create("src", minimalKeyConfig("my/secret", "k"));
            assertNotNull(source);
            source.close();
        }

        @Test
        @DisplayName("gateway AutoCloseable is invoked during construction")
        void gatewayClosedAfterEagerLoad() {
            AtomicBoolean closeCalled = new AtomicBoolean(false);

            // Use a concrete class that implements both SecretsGateway and AutoCloseable
            // so the instanceof check in AwsSecretsPropertySource fires.
            SecretsGateway autoCloseableGateway = new CloseRecordingGateway(closeCalled);

            List<SecretEntry> secrets = List.of(new SecretEntry("my/secret", null, "mykey"));
            new AwsSecretsPropertySource("src", autoCloseableGateway, secrets);

            assertTrue(closeCalled.get(), "gateway AutoCloseable.close() must be called after eager fetch");
        }

        /** Test double that is both a {@link SecretsGateway} and an {@link AutoCloseable}. */
        private static final class CloseRecordingGateway implements SecretsGateway, AutoCloseable {

            private final AtomicBoolean closeCalled;

            CloseRecordingGateway(AtomicBoolean closeCalled) {
                this.closeCalled = closeCalled;
            }

            @Override
            public String fetchSecretString(String secretId) {
                return "value";
            }

            @Override
            public void close() {
                closeCalled.set(true);
            }
        }
    }
}
