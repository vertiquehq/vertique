// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

import static dev.vertique.config.testing.ExceptionChainAssertions.containsAnywhere;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

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

    // --- Non-object secrets[] elements ---

    @Nested
    @DisplayName("non-object secrets[] elements")
    class NonObjectSecretsElements {

        /** Wraps {@code element} after one valid entry so the offending index is 1. */
        private JsonObject configWithElementAtIndexOne(Object element) {
            JsonArray secrets = new JsonArray()
                    .add(new JsonObject().put("secretId", "ok/secret").put("key", "ok.key"))
                    .add(element);
            return new JsonObject().put("secrets", secrets);
        }

        @Test
        @DisplayName("null, string, number, boolean and array elements throw ConfigPropertySourceException "
                + "naming the source and index")
        void nonObjectElementThrowsNamingSourceAndIndex() {
            for (Object element : Arrays.asList(null, "db/creds", 42, true, new JsonArray().add("x"))) {
                JsonObject config = configWithElementAtIndexOne(element);

                var ex = assertThrows(
                        ConfigPropertySourceException.class,
                        () -> factoryWithStub(Map.of("ok/secret", "v")).create("test-src", config),
                        "element " + element + " must be rejected with ConfigPropertySourceException");

                assertEquals("test-src", ex.sourceName(), "sourceName must equal the declared source name");
                assertTrue(ex.getMessage().contains("index 1"), "error must name the index; was: " + ex.getMessage());
                assertTrue(
                        ex.getMessage().contains("JSON object"),
                        "error must say a JSON object is required; was: " + ex.getMessage());
            }
        }

        @Test
        @DisplayName("the offending element's value is never echoed in the error")
        void nonObjectElementValueNotEchoed() {
            String sentinel = "NON-OBJECT-ELEMENT-SENTINEL-5521";
            JsonObject config = configWithElementAtIndexOne(sentinel);

            var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(Map.of("ok/secret", "v"))
                    .create("test-src", config));

            assertFalse(
                    containsAnywhere(ex, sentinel),
                    "exception chain must not echo the element; was: " + ex.getMessage());
        }

        @Test
        @DisplayName("validation fails before any gateway call")
        void nonObjectElementFailsBeforeFetch() {
            AtomicBoolean fetched = new AtomicBoolean(false);
            AwsSecretsPropertySourceFactory factory =
                    new AwsSecretsPropertySourceFactory((name, settings) -> secretId -> {
                        fetched.set(true);
                        return "value";
                    });

            assertThrows(
                    ConfigPropertySourceException.class,
                    () -> factory.create("test-src", configWithElementAtIndexOne("x")));

            assertFalse(fetched.get(), "schema failure must precede any SDK/gateway call");
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

    // --- Key collision: later declarations win ---

    @Nested
    @DisplayName("key collision — later declaration wins")
    class KeyCollision {

        @Test
        @DisplayName("two key-mode secrets targeting the same key: the later value wins")
        void laterKeyModeSecretWins() {
            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("secretId", "first")
                                            .put("key", "shared.key"))
                                    .add(new JsonObject()
                                            .put("secretId", "second")
                                            .put("key", "shared.key")));

            ConfigPropertySource source =
                    factoryWithStub(Map.of("first", "one", "second", "two")).create("src", config);

            assertEquals(Optional.of("two"), source.lookup("shared.key"));
        }

        @Test
        @DisplayName("two prefix-mode secrets sharing a prefix: overlapping keys take the later value, "
                + "disjoint keys from both survive")
        void laterPrefixModeSecretWinsOnOverlap() {
            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("secretId", "first")
                                            .put("prefix", "db."))
                                    .add(new JsonObject()
                                            .put("secretId", "second")
                                            .put("prefix", "db.")));

            ConfigPropertySource source = factoryWithStub(Map.of(
                            "first", "{\"password\":\"old\",\"user\":\"alice\"}",
                            "second", "{\"password\":\"new\",\"host\":\"db-host\"}"))
                    .create("src", config);

            assertEquals(Optional.of("new"), source.lookup("db.password"), "overlapping key: later wins");
            assertEquals(Optional.of("alice"), source.lookup("db.user"), "key only in the earlier secret survives");
            assertEquals(Optional.of("db-host"), source.lookup("db.host"), "key only in the later secret is added");
        }

        @Test
        @DisplayName("prefix-mode key colliding with a later key-mode key: the key-mode value wins")
        void laterKeyModeBeatsEarlierPrefixMode() {
            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("secretId", "blob")
                                            .put("prefix", "db."))
                                    .add(new JsonObject()
                                            .put("secretId", "plain")
                                            .put("key", "db.password")));

            ConfigPropertySource source = factoryWithStub(
                            Map.of("blob", "{\"password\":\"from-blob\"}", "plain", "from-key"))
                    .create("src", config);

            assertEquals(Optional.of("from-key"), source.lookup("db.password"));
        }

        @Test
        @DisplayName("key-mode key colliding with a later prefix-mode key: the prefix-mode value wins")
        void laterPrefixModeBeatsEarlierKeyMode() {
            JsonObject config = new JsonObject()
                    .put(
                            "secrets",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("secretId", "plain")
                                            .put("key", "db.password"))
                                    .add(new JsonObject()
                                            .put("secretId", "blob")
                                            .put("prefix", "db.")));

            ConfigPropertySource source = factoryWithStub(
                            Map.of("blob", "{\"password\":\"from-blob\"}", "plain", "from-key"))
                    .create("src", config);

            assertEquals(Optional.of("from-blob"), source.lookup("db.password"));
        }
    }

    // --- SdkSecretsGateway error handling ---

    @Nested
    @DisplayName("SdkSecretsGateway — SDK error handling")
    class SdkGatewayErrorHandling {

        /** Minimal client whose {@code getSecretValue} delegates to the supplied behavior. */
        private static SecretsManagerClient clientThrowing(RuntimeException toThrow) {
            return new StubSecretsManagerClient() {
                @Override
                public GetSecretValueResponse getSecretValue(GetSecretValueRequest request) {
                    throw toThrow;
                }
            };
        }

        @Test
        @DisplayName("SecretsManagerException with null awsErrorDetails() wraps as ConfigPropertySourceException")
        void nullAwsErrorDetailsWrapsWithoutNpe() {
            SecretsManagerException sdkFailure = (SecretsManagerException) SecretsManagerException.builder()
                    .message("sdk-level failure")
                    .build();
            assertNull(sdkFailure.awsErrorDetails(), "precondition: SDK exception carries no error details");

            SdkSecretsGateway gateway = new SdkSecretsGateway("aws-src", clientThrowing(sdkFailure));

            var ex = assertThrows(ConfigPropertySourceException.class, () -> gateway.fetchSecretString("prod/db"));

            assertEquals("aws-src", ex.sourceName());
            assertTrue(ex.getMessage().contains("prod/db"), "message must name the secret ID: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("sdk-level failure"),
                    "message must fall back to the SDK exception message: " + ex.getMessage());
            assertSame(sdkFailure, ex.getCause(), "the SDK exception must be preserved as the cause");
        }

        @Test
        @DisplayName("SecretsManagerException with neither details nor message falls back to the class name")
        void noDetailsAndNoMessageFallsBackToClassName() {
            SecretsManagerException sdkFailure =
                    (SecretsManagerException) SecretsManagerException.builder().build();

            SdkSecretsGateway gateway = new SdkSecretsGateway("aws-src", clientThrowing(sdkFailure));

            var ex = assertThrows(ConfigPropertySourceException.class, () -> gateway.fetchSecretString("prod/db"));

            assertTrue(
                    ex.getMessage().contains("SecretsManagerException"),
                    "message must fall back to the exception class name: " + ex.getMessage());
        }

        @Test
        @DisplayName("SecretsManagerException with awsErrorDetails() uses the SDK error message")
        void awsErrorDetailsMessageUsedWhenPresent() {
            SecretsManagerException sdkFailure = (SecretsManagerException) SecretsManagerException.builder()
                    .awsErrorDetails(AwsErrorDetails.builder()
                            .errorCode("AccessDeniedException")
                            .errorMessage("not authorized to read prod/db")
                            .build())
                    .message("outer message")
                    .build();

            SdkSecretsGateway gateway = new SdkSecretsGateway("aws-src", clientThrowing(sdkFailure));

            var ex = assertThrows(ConfigPropertySourceException.class, () -> gateway.fetchSecretString("prod/db"));

            assertTrue(
                    ex.getMessage().contains("not authorized to read prod/db"),
                    "message must carry the AWS error message: " + ex.getMessage());
        }

        @Test
        @DisplayName("binary secret (no SecretString) fails closed naming the secret ID")
        void binarySecretFailsClosed() {
            SdkSecretsGateway gateway = new SdkSecretsGateway("aws-src", new StubSecretsManagerClient() {
                @Override
                public GetSecretValueResponse getSecretValue(GetSecretValueRequest request) {
                    return GetSecretValueResponse.builder().build();
                }
            });

            var ex = assertThrows(ConfigPropertySourceException.class, () -> gateway.fetchSecretString("bin/secret"));

            assertTrue(ex.getMessage().contains("bin/secret") && ex.getMessage().contains("binary"));
        }

        @Test
        @DisplayName("non-SDK runtime failure wraps as ConfigPropertySourceException")
        void genericFailureWraps() {
            SdkSecretsGateway gateway =
                    new SdkSecretsGateway("aws-src", clientThrowing(new IllegalStateException("socket closed")));

            var ex = assertThrows(ConfigPropertySourceException.class, () -> gateway.fetchSecretString("prod/db"));

            assertTrue(ex.getMessage().contains("socket closed"));
        }

        @Test
        @DisplayName("close() closes the wrapped client")
        void closeClosesClient() {
            AtomicBoolean closed = new AtomicBoolean(false);
            SdkSecretsGateway gateway = new SdkSecretsGateway("aws-src", new StubSecretsManagerClient() {
                @Override
                public void close() {
                    closed.set(true);
                }
            });

            gateway.close();

            assertTrue(closed.get());
        }

        /** Base stub: every SDK operation is unsupported unless a test overrides it. */
        private abstract static class StubSecretsManagerClient implements SecretsManagerClient {

            @Override
            public String serviceName() {
                return "secretsmanager";
            }

            @Override
            public void close() {}
        }
    }

    // --- Failure-path log capture ---

    @Nested
    @DisplayName("redaction — failing create leaves no secret value in logs")
    class FailurePathLogging {

        @Test
        @DisplayName("prefix-mode parse failure after a successful fetch: no log event contains the secret value "
                + "or carries a throwable")
        void sentinelAbsentFromLogsOnFailingCreate() {
            String sentinel = "AWS-FAILURE-PATH-SENTINEL-8842";

            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.setContext(rootLogger.getLoggerContext());
            appender.start();
            rootLogger.addAppender(appender);

            try {
                JsonObject config = new JsonObject()
                        .put(
                                "secrets",
                                new JsonArray()
                                        .add(new JsonObject()
                                                .put("secretId", "good")
                                                .put("key", "good.key"))
                                        .add(new JsonObject()
                                                .put("secretId", "bad")
                                                .put("prefix", "db.")));

                // "bad" is not a JSON object and embeds the sentinel; "good" also holds the sentinel.
                var ex = assertThrows(ConfigPropertySourceException.class, () -> factoryWithStub(
                                Map.of("good", sentinel, "bad", "not-json-" + sentinel))
                        .create("src", config));

                assertFalse(containsAnywhere(ex, sentinel), "exception chain must not contain the secret value");

                for (var event : appender.list) {
                    assertFalse(
                            event.getFormattedMessage().contains(sentinel),
                            "log message must not contain the secret value: " + event.getFormattedMessage());
                    assertNull(
                            event.getThrowableProxy(),
                            "no log event may attach a throwable on the failure path: " + event.getFormattedMessage());
                }
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
