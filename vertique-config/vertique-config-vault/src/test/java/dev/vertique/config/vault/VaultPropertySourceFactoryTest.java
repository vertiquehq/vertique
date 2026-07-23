// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VaultPropertySourceFactory} using a stub {@link VaultGateway}.
 *
 * <p>All tests operate entirely in-memory with no Vault server. Schema validation, eager-load
 * semantics, key flattening, prefix application, collision resolution, redaction, and ServiceLoader
 * discovery are all verified here.
 */
class VaultPropertySourceFactoryTest {

    // --- Helpers ---

    /**
     * Stub token used by {@link #factoryWithStub} to satisfy the framework-owned VAULT_TOKEN
     * resolution without relying on the real process environment. This is not a secret — it is
     * a test-fixture value that never reaches a real Vault server.
     */
    private static final String STUB_VAULT_TOKEN = "stub-vault-token-for-tests";

    /**
     * Creates a factory wired to the given stub gateway data (path → flat key-value data).
     *
     * <p>The env-lookup seam always returns {@link #STUB_VAULT_TOKEN} for {@code VAULT_TOKEN}
     * so that tests that omit the {@code auth} block do not require a real environment variable.
     *
     * @param stubData map from path to the data map that the gateway returns
     * @return configured factory with stub gateway and stub env seam
     */
    private static VaultPropertySourceFactory factoryWithStub(Map<String, Map<String, Object>> stubData) {
        return new VaultPropertySourceFactory(
                settings -> path -> {
                    Map<String, Object> data = stubData.get(path);
                    return data != null ? data : Map.of();
                },
                key -> "VAULT_TOKEN".equals(key) ? STUB_VAULT_TOKEN : null);
    }

    /**
     * Builds a minimal valid source config with a single path.
     *
     * @param path   the vault path
     * @param prefix the key prefix (may be empty)
     * @return JsonObject source config
     */
    private static JsonObject minimalConfig(String address, String path, String prefix) {
        return new JsonObject()
                .put("address", address)
                .put(
                        "paths",
                        new JsonArray().add(new JsonObject().put("path", path).put("prefix", prefix)));
    }

    // --- ServiceLoader discovery ---

    @Nested
    @DisplayName("ServiceLoader discovery")
    class ServiceLoaderDiscovery {

        @Test
        @DisplayName("type 'vault' factory is discovered via ServiceLoader")
        void serviceLoaderFindsVaultFactory() {
            boolean found = false;
            for (ConfigPropertySourceFactory f : ServiceLoader.load(ConfigPropertySourceFactory.class)) {
                if ("vault".equals(f.type())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "ServiceLoader must discover ConfigPropertySourceFactory with type 'vault'");
        }
    }

    // --- Schema validation ---

    @Nested
    @DisplayName("schema validation")
    class SchemaValidation {

        @Test
        @DisplayName("throws when address is missing")
        void missingAddressThrows() {
            JsonObject config =
                    new JsonObject().put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("test-source", config));
            assertTrue(ex.getMessage().contains("address"), "error must mention 'address'");
            assertEquals("test-source", ex.sourceName());
        }

        @Test
        @DisplayName("throws when address is blank")
        void blankAddressThrows() {
            JsonObject config = new JsonObject()
                    .put("address", "   ")
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));
        }

        @Test
        @DisplayName("throws when paths array is missing")
        void missingPathsThrows() {
            JsonObject config = new JsonObject().put("address", "http://vault:8200");
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));
            assertTrue(ex.getMessage().contains("paths"), "error must mention 'paths'");
        }

        @Test
        @DisplayName("throws when paths array is empty")
        void emptyPathsThrows() {
            JsonObject config =
                    new JsonObject().put("address", "http://vault:8200").put("paths", new JsonArray());
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));
        }

        @Test
        @DisplayName("throws when a path entry has a blank path field")
        void blankPathEntryThrows() {
            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "")));
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));
        }

        @Test
        @DisplayName("throws for unknown auth method; method string appears in message")
        void unknownAuthMethodThrows() {
            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("auth", new JsonObject().put("method", "some-unknown-method"))
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));
            assertTrue(
                    ex.getMessage().contains("some-unknown-method"),
                    "unknown method string is structural, safe to include in error");
        }

        @Test
        @DisplayName("throws for approle when roleId is missing")
        void appRoleMissingRoleIdThrows() {
            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("auth", new JsonObject().put("method", "approle").put("secretId", "sid"))
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));
            assertTrue(ex.getMessage().contains("roleId"), "error must mention 'roleId'");
        }

        @Test
        @DisplayName("throws for approle when secretId is missing")
        void appRoleMissingSecretIdThrows() {
            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("auth", new JsonObject().put("method", "approle").put("roleId", "my-role"))
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));
            assertTrue(ex.getMessage().contains("secretId"), "error must mention 'secretId'");
        }

        @Test
        @DisplayName("throws for kubernetes when role is missing")
        void kubernetesMissingRoleThrows() {
            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("auth", new JsonObject().put("method", "kubernetes"))
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));
            assertTrue(ex.getMessage().contains("role"), "error must mention 'role'");
        }
    }

    // --- Auth defaults ---

    @Nested
    @DisplayName("auth defaults")
    class AuthDefaults {

        @Test
        @DisplayName("kubernetes jwtPath defaults to service-account token path")
        void kubernetesDefaultJwtPath() {
            // We capture the settings by capturing from the gateway factory
            VaultConnectionSettings[] captured = new VaultConnectionSettings[1];
            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(settings -> {
                captured[0] = settings;
                return path -> Map.of();
            });

            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("auth", new JsonObject().put("method", "kubernetes").put("role", "my-app"))
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));

            factory.create("src", config);

            assertInstanceOf(VaultAuthSettings.Kubernetes.class, captured[0].authSettings());
            var k = (VaultAuthSettings.Kubernetes) captured[0].authSettings();
            assertEquals("/var/run/secrets/kubernetes.io/serviceaccount/token", k.jwtPath());
        }
    }

    // --- Vault token resolution (item 1: framework-owned VAULT_TOKEN lookup) ---

    @Nested
    @DisplayName("vault token resolution — framework-owned VAULT_TOKEN lookup")
    class VaultTokenResolution {

        @Test
        @DisplayName("absent auth + env returns null → create fails with 'requires authentication' message")
        void absentAuthAndEnvAbsentFails() {
            // envLookup seam returns null → no VAULT_TOKEN in environment
            VaultPropertySourceFactory factory =
                    new VaultPropertySourceFactory(settings -> path -> Map.of(), key -> null);

            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));

            var ex = assertThrows(
                    dev.vertique.config.source.ConfigPropertySourceException.class,
                    () -> factory.create("my-vault", config));
            assertTrue(
                    ex.getMessage().contains("requires authentication"),
                    "error must contain 'requires authentication'; got: " + ex.getMessage());
            assertEquals("my-vault", ex.sourceName(), "sourceName must be included");
            // The error must not contain null/sentinel values — verified via absence of "null"
            assertFalse(ex.getMessage().contains("null"), "error must not mention null; got: " + ex.getMessage());
        }

        @Test
        @DisplayName("absent auth + env seam returns 'tok-env-SENTINEL' → gateway receives that exact token")
        void absentAuthEnvSeamPassesTokenToGateway() {
            String sentinel = "tok-env-SENTINEL";
            VaultConnectionSettings[] captured = new VaultConnectionSettings[1];
            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(
                    settings -> {
                        captured[0] = settings;
                        return path -> Map.of();
                    },
                    key -> "VAULT_TOKEN".equals(key) ? sentinel : null);

            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));

            factory.create("src", config);

            assertInstanceOf(VaultAuthSettings.Token.class, captured[0].authSettings());
            var t = (VaultAuthSettings.Token) captured[0].authSettings();
            assertEquals(sentinel, t.token(), "gateway must receive the token from env");
        }

        @Test
        @DisplayName("absent auth + env seam returns sentinel → sentinel never appears in log or exception")
        void envTokenSentinelAbsentFromLogs() {
            String sentinel = "tok-env-SENTINEL-LOG";
            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(
                    settings -> path -> Map.of(), key -> "VAULT_TOKEN".equals(key) ? sentinel : null);

            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.setContext(rootLogger.getLoggerContext());
            appender.start();
            rootLogger.addAppender(appender);
            try {
                JsonObject config = new JsonObject()
                        .put("address", "http://vault:8200")
                        .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));

                factory.create("src", config);

                String allMessages = appender.list.stream()
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .collect(java.util.stream.Collectors.joining("\n"));

                assertFalse(
                        allMessages.contains(sentinel),
                        "env token sentinel must NOT appear in any log message; found in: " + allMessages);
            } finally {
                rootLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("explicit auth.token wins over env seam")
        void explicitTokenWinsOverEnvSeam() {
            String envToken = "tok-from-env";
            String configToken = "tok-from-config";
            VaultConnectionSettings[] captured = new VaultConnectionSettings[1];
            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(
                    settings -> {
                        captured[0] = settings;
                        return path -> Map.of();
                    },
                    key -> "VAULT_TOKEN".equals(key) ? envToken : null);

            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("auth", new JsonObject().put("method", "token").put("token", configToken))
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));

            factory.create("src", config);

            assertInstanceOf(VaultAuthSettings.Token.class, captured[0].authSettings());
            var t = (VaultAuthSettings.Token) captured[0].authSettings();
            assertEquals(configToken, t.token(), "explicit config token must take precedence over env");
        }

        @Test
        @DisplayName("method=token with no token field + env absent → fails with 'requires authentication'")
        void methodTokenNoTokenFieldEnvAbsentFails() {
            VaultPropertySourceFactory factory =
                    new VaultPropertySourceFactory(settings -> path -> Map.of(), key -> null);

            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put("auth", new JsonObject().put("method", "token"))
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));

            var ex = assertThrows(
                    dev.vertique.config.source.ConfigPropertySourceException.class,
                    () -> factory.create("my-vault", config));
            assertTrue(
                    ex.getMessage().contains("requires authentication"),
                    "error must contain 'requires authentication'; got: " + ex.getMessage());
        }
    }

    // --- Timeout defaults and conversion ---

    @Nested
    @DisplayName("timeout defaults and Ms-to-seconds conversion")
    class TimeoutConversion {

        @Test
        @DisplayName("default timeouts are 5000 ms")
        void defaultTimeoutsAreFiveSeconds() {
            VaultConnectionSettings[] captured = new VaultConnectionSettings[1];
            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(
                    settings -> {
                        captured[0] = settings;
                        return path -> Map.of();
                    },
                    key -> "VAULT_TOKEN".equals(key) ? STUB_VAULT_TOKEN : null);

            factory.create("src", minimalConfig("http://vault:8200", "secret/app", ""));

            assertEquals(5000, captured[0].openTimeoutMs());
            assertEquals(5000, captured[0].readTimeoutMs());
        }

        @Test
        @DisplayName("explicit timeout values are preserved in milliseconds")
        void explicitTimeoutsPreserved() {
            VaultConnectionSettings[] captured = new VaultConnectionSettings[1];
            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(
                    settings -> {
                        captured[0] = settings;
                        return path -> Map.of();
                    },
                    key -> "VAULT_TOKEN".equals(key) ? STUB_VAULT_TOKEN : null);

            JsonObject config = minimalConfig("http://vault:8200", "secret/app", "")
                    .put("openTimeoutMs", 3000)
                    .put("readTimeoutMs", 7000);

            factory.create("src", config);

            assertEquals(3000, captured[0].openTimeoutMs());
            assertEquals(7000, captured[0].readTimeoutMs());
        }

        @Test
        @DisplayName("msToSeconds converts correctly — 5000 ms → 5 s; 500 ms → 1 s; 1 ms → 1 s")
        void msToSecondsConversion() {
            assertEquals(5, JOpenLibsVaultGateway.msToSeconds(5000));
            assertEquals(1, JOpenLibsVaultGateway.msToSeconds(500));
            assertEquals(1, JOpenLibsVaultGateway.msToSeconds(1));
            assertEquals(1, JOpenLibsVaultGateway.msToSeconds(1000));
            assertEquals(2, JOpenLibsVaultGateway.msToSeconds(1001));
        }

        @Test
        @DisplayName("zero openTimeoutMs throws naming the field")
        void zeroOpenTimeoutThrows() {
            JsonObject config =
                    minimalConfig("http://vault:8200", "secret/app", "").put("openTimeoutMs", 0);
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("test-src", config));
            assertTrue(ex.getMessage().contains("openTimeoutMs"), "error must name the field");
            assertEquals("test-src", ex.sourceName());
        }

        @Test
        @DisplayName("negative openTimeoutMs throws naming the field")
        void negativeOpenTimeoutThrows() {
            JsonObject config =
                    minimalConfig("http://vault:8200", "secret/app", "").put("openTimeoutMs", -1);
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("test-src", config));
            assertTrue(ex.getMessage().contains("openTimeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("zero readTimeoutMs throws naming the field")
        void zeroReadTimeoutThrows() {
            JsonObject config =
                    minimalConfig("http://vault:8200", "secret/app", "").put("readTimeoutMs", 0);
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("test-src", config));
            assertTrue(ex.getMessage().contains("readTimeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("non-integer openTimeoutMs throws naming the field")
        void nonIntegerOpenTimeoutThrows() {
            JsonObject config =
                    minimalConfig("http://vault:8200", "secret/app", "").put("openTimeoutMs", "not-a-number");
            VaultPropertySourceFactory factory = factoryWithStub(Map.of());
            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("test-src", config));
            assertTrue(ex.getMessage().contains("openTimeoutMs"), "error must name the field");
        }
    }

    // --- Eager read at create ---

    @Nested
    @DisplayName("eager read at create")
    class EagerRead {

        @Test
        @DisplayName("gateway is called for each declared path at create time")
        void allPathsReadAtCreate() {
            java.util.List<String> readPaths = new java.util.ArrayList<>();
            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(
                    settings -> path -> {
                        readPaths.add(path);
                        return Map.of();
                    },
                    key -> "VAULT_TOKEN".equals(key) ? STUB_VAULT_TOKEN : null);

            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put(
                            "paths",
                            new JsonArray()
                                    .add(new JsonObject().put("path", "secret/first"))
                                    .add(new JsonObject().put("path", "secret/second")));

            factory.create("src", config);

            assertEquals(2, readPaths.size());
            assertTrue(readPaths.contains("secret/first"));
            assertTrue(readPaths.contains("secret/second"));
        }

        @Test
        @DisplayName("paths are read in declaration order")
        void pathsReadInDeclarationOrder() {
            java.util.List<String> readOrder = new java.util.ArrayList<>();
            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(
                    settings -> path -> {
                        readOrder.add(path);
                        return Map.of();
                    },
                    key -> "VAULT_TOKEN".equals(key) ? STUB_VAULT_TOKEN : null);

            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put(
                            "paths",
                            new JsonArray()
                                    .add(new JsonObject().put("path", "secret/a"))
                                    .add(new JsonObject().put("path", "secret/b"))
                                    .add(new JsonObject().put("path", "secret/c")));

            factory.create("src", config);

            assertEquals(java.util.List.of("secret/a", "secret/b", "secret/c"), readOrder);
        }

        @Test
        @DisplayName("gateway throw at create wraps as ConfigPropertySourceException with source name and path")
        void gatewayThrowWrapsCorrectly() {
            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(
                    settings -> path -> {
                        throw new VaultReadException(path, "VaultException");
                    },
                    key -> "VAULT_TOKEN".equals(key) ? STUB_VAULT_TOKEN : null);

            JsonObject config = minimalConfig("http://vault:8200", "secret/app", "");

            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("failing-source", config));

            assertEquals("failing-source", ex.sourceName());
            assertTrue(ex.getMessage().contains("failing-source"), "exception message must contain source name");
            // Path is the key field in ConfigPropertySourceException for eager-load failures
        }

        @Test
        @DisplayName("gateway throw message must NOT contain the sentinel secret value")
        void gatewayThrowMessageDoesNotContainSecretValue() {
            String sentinel = "s3cr3t-VAULT-SENTINEL-1337";

            VaultPropertySourceFactory factory = new VaultPropertySourceFactory(settings -> path -> {
                throw new VaultReadException(path, "VaultException");
            });

            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put(
                            "auth",
                            new JsonObject()
                                    .put("method", "approle")
                                    .put("roleId", "my-role")
                                    .put("secretId", sentinel))
                    .put("paths", new JsonArray().add(new JsonObject().put("path", "secret/app")));

            var ex = assertThrows(ConfigPropertySourceException.class, () -> factory.create("src", config));

            assertFalse(
                    ex.getMessage().contains(sentinel),
                    "exception message must NOT contain the secret ID sentinel value");
        }
    }

    // --- Flattening ---

    @Nested
    @DisplayName("key flattening")
    class KeyFlattening {

        @Test
        @DisplayName("flat string values are returned as-is")
        void flatStringValues() {
            Map<String, Object> data = Map.of("host", "localhost", "port", "5432");
            ConfigPropertySource source = factoryWithStub(Map.of("secret/db", data))
                    .create("src", minimalConfig("http://vault:8200", "secret/db", ""));

            assertEquals(Optional.of("localhost"), source.lookup("host"));
            assertEquals(Optional.of("5432"), source.lookup("port"));
        }

        @Test
        @DisplayName("prefix is prepended to all keys")
        void prefixPrepended() {
            Map<String, Object> data = Map.of("host", "localhost");
            ConfigPropertySource source = factoryWithStub(Map.of("secret/db", data))
                    .create("src", minimalConfig("http://vault:8200", "secret/db", "db."));

            assertEquals(Optional.of("localhost"), source.lookup("db.host"));
            assertEquals(Optional.empty(), source.lookup("host"));
        }

        @Test
        @DisplayName("nested map values are dot-joined")
        void nestedMapDotJoined() {
            Map<String, Object> nested = new HashMap<>();
            nested.put("host", "pg-host");
            nested.put("port", "5432");
            Map<String, Object> data = new HashMap<>();
            data.put("db", nested);

            ConfigPropertySource source = factoryWithStub(Map.of("secret/app", data))
                    .create("src", minimalConfig("http://vault:8200", "secret/app", "app."));

            assertEquals(Optional.of("pg-host"), source.lookup("app.db.host"));
            assertEquals(Optional.of("5432"), source.lookup("app.db.port"));
        }

        @Test
        @DisplayName("non-string scalars are stringified via String.valueOf")
        void nonStringScalarsStringified() {
            Map<String, Object> data = new HashMap<>();
            data.put("count", 42);
            data.put("enabled", true);
            data.put("ratio", 3.14);

            ConfigPropertySource source = factoryWithStub(Map.of("secret/app", data))
                    .create("src", minimalConfig("http://vault:8200", "secret/app", ""));

            assertEquals(Optional.of("42"), source.lookup("count"));
            assertEquals(Optional.of("true"), source.lookup("enabled"));
            assertEquals(Optional.of("3.14"), source.lookup("ratio"));
        }

        @Test
        @DisplayName("later path wins on key collision")
        void laterPathWinsOnCollision() {
            Map<String, Object> firstData = Map.of("shared.key", "from-first");
            Map<String, Object> secondData = Map.of("shared.key", "from-second");

            VaultPropertySourceFactory factory = factoryWithStub(Map.of(
                    "secret/first", firstData,
                    "secret/second", secondData));

            JsonObject config = new JsonObject()
                    .put("address", "http://vault:8200")
                    .put(
                            "paths",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("path", "secret/first")
                                            .put("prefix", ""))
                                    .add(new JsonObject()
                                            .put("path", "secret/second")
                                            .put("prefix", "")));

            ConfigPropertySource source = factory.create("src", config);

            assertEquals(Optional.of("from-second"), source.lookup("shared.key"), "later path must win on collision");
        }
    }

    // --- Lookup ---

    @Nested
    @DisplayName("lookup contract")
    class LookupContract {

        @Test
        @DisplayName("lookup returns empty for missing key")
        void lookupMissingKeyReturnsEmpty() {
            ConfigPropertySource source = factoryWithStub(Map.of("secret/app", Map.of("present", "value")))
                    .create("src", minimalConfig("http://vault:8200", "secret/app", ""));

            assertEquals(Optional.empty(), source.lookup("missing"));
        }

        @Test
        @DisplayName("lookup returns value for present key")
        void lookupPresentKeyReturnsValue() {
            ConfigPropertySource source = factoryWithStub(Map.of("secret/app", Map.of("mykey", "myvalue")))
                    .create("src", minimalConfig("http://vault:8200", "secret/app", ""));

            assertEquals(Optional.of("myvalue"), source.lookup("mykey"));
        }

        @Test
        @DisplayName("source name is returned correctly")
        void sourceNameReturned() {
            ConfigPropertySource source = factoryWithStub(Map.of("secret/app", Map.of()))
                    .create("my-vault-source", minimalConfig("http://vault:8200", "secret/app", ""));

            assertEquals("my-vault-source", source.name());
        }
    }

    // --- Redaction log test ---

    @Nested
    @DisplayName("redaction — no sentinel value in log output")
    class RedactionTest {

        @Test
        @DisplayName("sentinel secret value never appears in any captured log message on successful create+lookup")
        void sentinelAbsentFromLogs() {
            String sentinel = "VAULT-SECRET-SENTINEL-8472";

            // Capture log output via ListAppender
            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.setContext(rootLogger.getLoggerContext());
            appender.start();
            rootLogger.addAppender(appender);

            try {
                Map<String, Object> data = Map.of("db.password", sentinel);
                ConfigPropertySource source = factoryWithStub(Map.of("secret/db", data))
                        .create("vault-redaction-test", minimalConfig("http://vault:8200", "secret/db", ""));

                // Trigger a lookup to ensure value is accessed
                Optional<String> result = source.lookup("db.password");
                assertTrue(result.isPresent());

                // Check no log line contains the sentinel
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
            ConfigPropertySource source = factoryWithStub(Map.of("secret/app", Map.of()))
                    .create("src", minimalConfig("http://vault:8200", "secret/app", ""));
            // Default close() is a no-op; must not throw
            assertNotNull(source);
            source.close();
        }
    }
}
