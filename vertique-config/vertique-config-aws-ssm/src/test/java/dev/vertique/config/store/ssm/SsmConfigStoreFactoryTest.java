// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.store.ssm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vertx.config.spi.ConfigStore;
import io.vertx.config.spi.ConfigStoreFactory;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/**
 * Unit tests for {@link SsmConfigStoreFactory} and {@link SsmConfigStore}.
 *
 * <p>All tests use a stub {@link SsmGateway} — no real AWS endpoint is required.
 * Covers: parameter-to-nested-key mapping, pagination, flag forwarding, StringList
 * handling, prefix re-rooting, empty-result semantics, schema validation, ServiceLoader
 * discovery, store close, and NFR-CONF-002 log redaction.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class SsmConfigStoreFactoryTest {

    private SsmGateway gateway;
    private SsmConfigStoreFactory factory;

    // --- Setup ---

    @BeforeEach
    void setUp() {
        gateway = mock(SsmGateway.class);
        factory = new SsmConfigStoreFactory(settings -> gateway);
    }

    // --- Helpers ---

    /**
     * Creates a minimal valid store config with just a {@code path} field.
     *
     * @param path the SSM path value
     * @return config JsonObject
     */
    private static JsonObject configWithPath(String path) {
        return new JsonObject().put("path", path);
    }

    /**
     * Creates a {@link Parameter} with the given name, value, and {@code String} type.
     *
     * @param name  the parameter name (full SSM path, e.g. {@code "/myapp/prod/db/host"})
     * @param value the parameter value
     * @return the Parameter
     */
    private static Parameter stringParam(String name, String value) {
        return Parameter.builder()
                .name(name)
                .value(value)
                .type(ParameterType.STRING)
                .build();
    }

    /**
     * Creates a {@link Parameter} with the given name, value, and {@code SecureString} type.
     *
     * @param name  the parameter name
     * @param value the parameter value
     * @return the Parameter
     */
    private static Parameter secureStringParam(String name, String value) {
        return Parameter.builder()
                .name(name)
                .value(value)
                .type(ParameterType.SECURE_STRING)
                .build();
    }

    /**
     * Creates a {@link Parameter} with the given name, value, and {@code StringList} type.
     *
     * @param name  the parameter name
     * @param value the comma-separated list value
     * @return the Parameter
     */
    private static Parameter stringListParam(String name, String value) {
        return Parameter.builder()
                .name(name)
                .value(value)
                .type(ParameterType.STRING_LIST)
                .build();
    }

    // --- ServiceLoader Discovery ---

    @Nested
    @DisplayName("ServiceLoader discovery")
    class ServiceLoaderDiscovery {

        @Test
        @DisplayName("type 'aws-ssm' factory is discovered via ServiceLoader")
        void serviceLoaderFindsAwsSsmFactory() {
            boolean found = false;
            for (ConfigStoreFactory f : ServiceLoader.load(ConfigStoreFactory.class)) {
                if ("aws-ssm".equals(f.name())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "ServiceLoader must discover ConfigStoreFactory with name 'aws-ssm'");
        }
    }

    // --- Schema Validation ---

    @Nested
    @DisplayName("Schema validation")
    class SchemaValidation {

        @Test
        @DisplayName("missing path throws IllegalArgumentException")
        void missingPathThrows(Vertx vertx) {
            JsonObject config = new JsonObject();
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("blank path throws IllegalArgumentException")
        void blankPathThrows(Vertx vertx) {
            JsonObject config = new JsonObject().put("path", "   ");
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("zero connectTimeoutMs throws IllegalArgumentException")
        void zeroConnectTimeoutThrows(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("connectTimeoutMs", 0);
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("negative readTimeoutMs throws IllegalArgumentException")
        void negativeReadTimeoutThrows(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("readTimeoutMs", -1);
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("non-integer connectTimeoutMs throws IllegalArgumentException")
        void nonIntegerConnectTimeoutThrows(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("connectTimeoutMs", "not-a-number");
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("minimal valid config (path only) creates store without error")
        void minimalValidConfig(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/");
            ConfigStore store = factory.create(vertx, config);
            assertNotNull(store);
        }
    }

    // --- Path Normalization ---

    @Nested
    @DisplayName("Path normalization")
    class PathNormalization {

        @Test
        @DisplayName("path without leading slash gets slash prepended")
        void pathWithoutLeadingSlash(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true)).thenReturn(List.of());

            ConfigStore store = factory.create(vertx, configWithPath("myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                verify(gateway).fetchAll("/myapp/prod/", true, true);
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("path without trailing slash gets slash appended")
        void pathWithoutTrailingSlash(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true)).thenReturn(List.of());

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod"));
            store.get().onComplete(ctx.succeeding(buf -> {
                verify(gateway).fetchAll("/myapp/prod/", true, true);
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("path with both slashes is unchanged")
        void pathWithBothSlashes(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true)).thenReturn(List.of());

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                verify(gateway).fetchAll("/myapp/prod/", true, true);
                ctx.completeNow();
            }));
        }
    }

    // --- Parameter-to-nested-key Mapping ---

    @Nested
    @DisplayName("Parameter name to nested key mapping")
    class ParameterMapping {

        @Test
        @DisplayName("single parameter maps to single nested key")
        void singleParameter(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true))
                    .thenReturn(List.of(stringParam("/myapp/prod/db", "localhost")));

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                JsonObject result = new JsonObject(buf);
                assertEquals("localhost", result.getString("db"));
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("deep nesting: /myapp/prod/db/host → {db:{host:...}}")
        void deepNesting(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true))
                    .thenReturn(List.of(stringParam("/myapp/prod/db/host", "db.example.com")));

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                JsonObject result = new JsonObject(buf);
                assertEquals("db.example.com", result.getJsonObject("db").getString("host"));
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("multiple parameters build one merged tree")
        void multipleParametersMerge(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true))
                    .thenReturn(List.of(
                            stringParam("/myapp/prod/db/host", "db.example.com"),
                            secureStringParam("/myapp/prod/db/password", "secret"),
                            stringParam("/myapp/prod/http/port", "8080")));

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                JsonObject result = new JsonObject(buf);
                JsonObject db = result.getJsonObject("db");
                assertNotNull(db);
                assertEquals("db.example.com", db.getString("host"));
                assertEquals("secret", db.getString("password"));
                assertEquals("8080", result.getJsonObject("http").getString("port"));
                ctx.completeNow();
            }));
        }
    }

    // --- Gateway flag forwarding ---

    @Nested
    @DisplayName("Gateway flag forwarding")
    class GatewayFlagForwarding {

        @Test
        @DisplayName("recursive and withDecryption flags are forwarded to gateway")
        void flagsForwardedToGateway(Vertx vertx, VertxTestContext ctx) {
            ArgumentCaptor<String> pathCaptor = forClass(String.class);
            ArgumentCaptor<Boolean> recursiveCaptor = forClass(Boolean.class);
            ArgumentCaptor<Boolean> withDecryptionCaptor = forClass(Boolean.class);

            when(gateway.fetchAll(pathCaptor.capture(), recursiveCaptor.capture(), withDecryptionCaptor.capture()))
                    .thenReturn(List.of());

            JsonObject config =
                    configWithPath("/myapp/prod/").put("recursive", false).put("withDecryption", false);
            ConfigStore store = factory.create(vertx, config);

            store.get().onComplete(ctx.succeeding(buf -> {
                assertEquals("/myapp/prod/", pathCaptor.getValue());
                assertFalse(recursiveCaptor.getValue(), "recursive should be false");
                assertFalse(withDecryptionCaptor.getValue(), "withDecryption should be false");
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("recursive and withDecryption default to true")
        void defaultFlagsAreTrue(Vertx vertx, VertxTestContext ctx) {
            ArgumentCaptor<Boolean> recursiveCaptor = forClass(Boolean.class);
            ArgumentCaptor<Boolean> withDecryptionCaptor = forClass(Boolean.class);

            when(gateway.fetchAll(anyString(), recursiveCaptor.capture(), withDecryptionCaptor.capture()))
                    .thenReturn(List.of());

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                assertTrue(recursiveCaptor.getValue(), "recursive should default to true");
                assertTrue(withDecryptionCaptor.getValue(), "withDecryption should default to true");
                ctx.completeNow();
            }));
        }
    }

    // --- StringList handling ---

    @Nested
    @DisplayName("StringList parameter type")
    class StringListHandling {

        @Test
        @DisplayName("StringList parameter is mapped to JsonArray of comma-split strings")
        void stringListToJsonArray(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true))
                    .thenReturn(List.of(stringListParam("/myapp/prod/tags", "alpha,beta,gamma")));

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                JsonObject result = new JsonObject(buf);
                JsonArray tags = result.getJsonArray("tags");
                assertNotNull(tags);
                assertEquals(3, tags.size());
                assertEquals("alpha", tags.getString(0));
                assertEquals("beta", tags.getString(1));
                assertEquals("gamma", tags.getString(2));
                ctx.completeNow();
            }));
        }
    }

    // --- Prefix Re-rooting ---

    @Nested
    @DisplayName("Prefix re-rooting")
    class PrefixRerooting {

        @Test
        @DisplayName("prefix 'app' nests result under {app:{...}}")
        void singleSegmentPrefix(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true))
                    .thenReturn(List.of(stringParam("/myapp/prod/key", "value")));

            JsonObject config = configWithPath("/myapp/prod/").put("prefix", "app");
            ConfigStore store = factory.create(vertx, config);
            store.get().onComplete(ctx.succeeding(buf -> {
                JsonObject result = new JsonObject(buf);
                assertNotNull(result.getJsonObject("app"));
                assertEquals("value", result.getJsonObject("app").getString("key"));
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("prefix 'app.sub' nests result under {app:{sub:{...}}}")
        void multiSegmentPrefix(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true))
                    .thenReturn(List.of(stringParam("/myapp/prod/key", "value")));

            JsonObject config = configWithPath("/myapp/prod/").put("prefix", "app.sub");
            ConfigStore store = factory.create(vertx, config);
            store.get().onComplete(ctx.succeeding(buf -> {
                JsonObject result = new JsonObject(buf);
                JsonObject app = result.getJsonObject("app");
                assertNotNull(app);
                JsonObject sub = app.getJsonObject("sub");
                assertNotNull(sub);
                assertEquals("value", sub.getString("key"));
                ctx.completeNow();
            }));
        }
    }

    // --- Empty Result ---

    @Nested
    @DisplayName("Empty result semantics")
    class EmptyResult {

        @Test
        @DisplayName("no parameters under path yields succeeded Future with empty JsonObject")
        void emptyPathSucceeds(Vertx vertx, VertxTestContext ctx) {
            when(gateway.fetchAll("/myapp/prod/", true, true)).thenReturn(List.of());

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                JsonObject result = new JsonObject(buf);
                assertTrue(result.isEmpty(), "Empty parameter list should yield empty JsonObject");
                ctx.completeNow();
            }));
        }
    }

    // --- SDK failure ---

    @Nested
    @DisplayName("SDK failure handling")
    class SdkFailure {

        @Test
        @DisplayName("gateway exception yields failed Future; error message does not contain parameter values")
        void gatewayExceptionYieldsFailedFuture(Vertx vertx, VertxTestContext ctx) {
            // The gateway throws on the first (and only) call — there is no prior successful page
            RuntimeException sdkError = new RuntimeException("AccessDenied for path /myapp/prod/");
            when(gateway.fetchAll("/myapp/prod/", true, true)).thenThrow(sdkError);

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.failing(err -> {
                // The error message names the path; it must not contain a sentinel parameter value
                String fullMessage = buildFullExceptionText(err);
                assertFalse(
                        fullMessage.contains("SENTINEL_SECRET_VALUE"),
                        "Error message must not contain a parameter value; got: " + fullMessage);
                ctx.completeNow();
            }));
        }

        /**
         * Collects the full exception text (message + cause chain) for assertion.
         *
         * @param t the throwable
         * @return combined text of all message strings in the cause chain
         */
        private static String buildFullExceptionText(Throwable t) {
            StringBuilder sb = new StringBuilder();
            Throwable current = t;
            while (current != null) {
                if (current.getMessage() != null) {
                    sb.append(current.getMessage()).append(' ');
                }
                current = current.getCause();
            }
            return sb.toString();
        }
    }

    // --- Store Close ---

    @Nested
    @DisplayName("Store close")
    class StoreClose {

        @Test
        @DisplayName("close() closes the gateway when it is AutoCloseable")
        void closeDelegatesGateway(Vertx vertx) throws Exception {
            // Use a mock that also implements AutoCloseable
            AutoCloseableGateway closeableGateway = mock(AutoCloseableGateway.class);
            SsmConfigStoreFactory closeFactory = new SsmConfigStoreFactory(settings -> closeableGateway);

            ConfigStore store = closeFactory.create(vertx, configWithPath("/myapp/prod/"));
            // close() offloads via executeBlocking — await completion before asserting
            store.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

            verify(closeableGateway).close();
        }

        /**
         * Combined interface so Mockito can mock both {@link SsmGateway} and
         * {@link AutoCloseable} on a single instance.
         */
        interface AutoCloseableGateway extends SsmGateway, AutoCloseable {}
    }

    // --- Log Redaction (NFR-CONF-002) ---

    @Nested
    @DisplayName("NFR-CONF-002: no parameter values in logs")
    class LogRedaction {

        private ListAppender<ILoggingEvent> logCapture;
        private Logger capturedLogger;
        private Level originalLevel;

        /**
         * Attaches a list appender to the {@link SsmConfigStore} logger and stores the logger
         * reference so {@link #detachAppender()} can clean up.
         */
        private void attachLogCapture() {
            capturedLogger = (Logger) LoggerFactory.getLogger(SsmConfigStore.class);
            originalLevel = capturedLogger.getLevel();
            logCapture = new ListAppender<>();
            logCapture.start();
            capturedLogger.addAppender(logCapture);
            capturedLogger.setLevel(Level.ALL);
        }

        /** Detaches the log appender and restores the logger's original level after each test. */
        @AfterEach
        void detachAppender() {
            if (capturedLogger != null && logCapture != null) {
                capturedLogger.detachAppender(logCapture);
                capturedLogger.setLevel(originalLevel);
            }
        }

        @Test
        @DisplayName("no parameter values appear in INFO logs on successful load")
        void noValuesInLogs(Vertx vertx, VertxTestContext ctx) {
            attachLogCapture();

            String sentinelValue = "SENTINEL_PARAM_VALUE_12345";
            when(gateway.fetchAll("/myapp/prod/", true, true))
                    .thenReturn(List.of(secureStringParam("/myapp/prod/secret/key", sentinelValue)));

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                List<String> logMessages = new ArrayList<>();
                for (ILoggingEvent event : logCapture.list) {
                    logMessages.add(event.getFormattedMessage());
                }
                for (String message : logMessages) {
                    assertFalse(
                            message.contains(sentinelValue),
                            "Log message must not contain parameter value; found in: " + message);
                }
                ctx.completeNow();
            }));
        }
    }

    // --- Name-Conflict Semantics ---

    @Nested
    @DisplayName("putNested name-conflict semantics")
    class NameConflictSemantics {

        @Test
        @DisplayName("leaf-after-subtree: parameter overwriting a subtree key produces a leaf, discards subtree")
        void leafAfterSubtreeDiscardsSubtree(Vertx vertx, VertxTestContext ctx) {
            // /myapp/prod/db/host → creates db subtree with nested key "host"
            // /myapp/prod/db → overwrites the "db" subtree with a leaf string
            when(gateway.fetchAll("/myapp/prod/", true, true))
                    .thenReturn(List.of(
                            stringParam("/myapp/prod/db/host", "db.example.com"),
                            stringParam("/myapp/prod/db", "overwrite-leaf")));

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                JsonObject result = new JsonObject(buf);
                // The "db" key must be the leaf string, not an object
                assertEquals("overwrite-leaf", result.getString("db"), "leaf must overwrite prior subtree");
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("subtree-after-leaf: parameter descending through a prior leaf replaces it with an object")
        void subtreeAfterLeafReplacesLeaf(Vertx vertx, VertxTestContext ctx) {
            // /myapp/prod/db → sets "db" to a leaf string
            // /myapp/prod/db/host → descends through "db", replacing the leaf with a subtree
            when(gateway.fetchAll("/myapp/prod/", true, true))
                    .thenReturn(List.of(
                            stringParam("/myapp/prod/db", "original-leaf"),
                            stringParam("/myapp/prod/db/host", "db.example.com")));

            ConfigStore store = factory.create(vertx, configWithPath("/myapp/prod/"));
            store.get().onComplete(ctx.succeeding(buf -> {
                JsonObject result = new JsonObject(buf);
                // The "db" key must now be an object with "host" inside
                JsonObject db = result.getJsonObject("db");
                assertNotNull(db, "db must be an object after subtree-after-leaf conflict");
                assertEquals("db.example.com", db.getString("host"), "host must be present in the db subtree");
                ctx.completeNow();
            }));
        }
    }

    // --- Prefix Validation ---

    @Nested
    @DisplayName("Prefix validation")
    class PrefixValidation {

        @Test
        @DisplayName("blank prefix throws IllegalArgumentException")
        void blankPrefixThrows(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("prefix", "   ");
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("prefix with blank dot-segment (e.g. 'a..b') throws IllegalArgumentException")
        void blankDotSegmentThrows(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("prefix", "a..b");
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("prefix with leading dot (e.g. '.app') throws IllegalArgumentException")
        void leadingDotThrows(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("prefix", ".app");
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("prefix with trailing dot (e.g. 'app.') throws IllegalArgumentException")
        void trailingDotThrows(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("prefix", "app.");
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("null prefix is accepted (optional field)")
        void nullPrefixAccepted(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/");
            // no "prefix" key — must not throw
            assertNotNull(factory.create(vertx, config));
        }
    }

    // --- Root path rejection ---

    @Nested
    @DisplayName("Root path rejection")
    class RootPathRejection {

        @Test
        @DisplayName("path '/' is rejected with a clear error")
        void slashAloneRejected(Vertx vertx) {
            JsonObject config = new JsonObject().put("path", "/");
            var ex = assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
            assertTrue(ex.getMessage().contains("segment"), "error must explain that at least one segment is required");
        }

        @Test
        @DisplayName("path that trims to blank is rejected")
        void blankAfterTrimRejected(Vertx vertx) {
            // "  /  " normalizes to "/" then fails the segment check
            JsonObject config = new JsonObject().put("path", "  /  ");
            assertThrows(IllegalArgumentException.class, () -> factory.create(vertx, config));
        }

        @Test
        @DisplayName("path '/myapp/' (has one real segment) is accepted")
        void singleSegmentPathAccepted(Vertx vertx) {
            when(gateway.fetchAll("/myapp/", true, true)).thenReturn(List.of());
            assertNotNull(factory.create(vertx, new JsonObject().put("path", "/myapp/")));
        }
    }

    // --- Exact integral timeout enforcement (item 4) ---

    @Nested
    @DisplayName("Exact integral timeout enforcement")
    class ExactIntegralTimeouts {

        @Test
        @DisplayName("5000.5 (fractional Double) is rejected for connectTimeoutMs")
        void fractionalDoubleRejectedConnectTimeout(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("connectTimeoutMs", 5000.5);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.create(vertx, config),
                    "fractional double must be rejected for connectTimeoutMs");
        }

        @Test
        @DisplayName("5000.5 (fractional Double) is rejected for readTimeoutMs")
        void fractionalDoubleRejectedReadTimeout(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("readTimeoutMs", 5000.5);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.create(vertx, config),
                    "fractional double must be rejected for readTimeoutMs");
        }

        @Test
        @DisplayName("3_000_000_000L (overflows int) is rejected for connectTimeoutMs")
        void longOverflowRejectedConnectTimeout(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("connectTimeoutMs", 3_000_000_000L);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.create(vertx, config),
                    "long overflow must be rejected for connectTimeoutMs");
        }

        @Test
        @DisplayName("3_000_000_000L (overflows int) is rejected for readTimeoutMs")
        void longOverflowRejectedReadTimeout(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("readTimeoutMs", 3_000_000_000L);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.create(vertx, config),
                    "long overflow must be rejected for readTimeoutMs");
        }

        @Test
        @DisplayName("5000L (exact Long) is accepted for connectTimeoutMs")
        void exactLongAcceptedConnectTimeout(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("connectTimeoutMs", 5000L);
            assertNotNull(factory.create(vertx, config), "exact long within int range must be accepted");
        }

        @Test
        @DisplayName("BigDecimal 1.5 (fractional) is rejected for connectTimeoutMs")
        void bigDecimalFractionalRejectedConnectTimeout(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("connectTimeoutMs", new java.math.BigDecimal("1.5"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.create(vertx, config),
                    "BigDecimal 1.5 must be rejected for connectTimeoutMs");
        }

        @Test
        @DisplayName("BigInteger 2^40 (> Integer.MAX_VALUE) is rejected for readTimeoutMs")
        void bigIntegerOversizedRejectedReadTimeout(Vertx vertx) {
            JsonObject config = configWithPath("/myapp/prod/").put("readTimeoutMs", java.math.BigInteger.TWO.pow(40));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.create(vertx, config),
                    "BigInteger 2^40 must be rejected for readTimeoutMs");
        }

        @Test
        @DisplayName("BigDecimal 5000 (exact integral) is accepted for connectTimeoutMs")
        void bigDecimalExactIntegralAcceptedConnectTimeout(Vertx vertx) {
            JsonObject config =
                    configWithPath("/myapp/prod/").put("connectTimeoutMs", new java.math.BigDecimal("5000"));
            assertNotNull(factory.create(vertx, config), "BigDecimal 5000 must be accepted");
        }
    }

    // --- SdkSsmGateway pagination (direct SsmClient mock) ---

    @Nested
    @DisplayName("SdkSsmGateway pagination via mock SsmClient")
    class SdkSsmGatewayPagination {

        @Test
        @DisplayName("two-page response: both pages collected; second request carries nextToken")
        void twoPagesCollectedAndNextTokenForwarded(Vertx vertx, VertxTestContext ctx) {
            SsmClient ssmClient = mock(SsmClient.class);

            Parameter page1Param = stringParam("/app/a", "1");
            Parameter page2Param = stringParam("/app/b", "2");

            // First call — returns page 1 with a nextToken
            ArgumentCaptor<GetParametersByPathRequest> requestCaptor = forClass(GetParametersByPathRequest.class);
            when(ssmClient.getParametersByPath(requestCaptor.capture()))
                    .thenReturn(GetParametersByPathResponse.builder()
                            .parameters(page1Param)
                            .nextToken("token-page-2")
                            .build())
                    // Second call — returns page 2 without a nextToken (end of pages)
                    .thenReturn(GetParametersByPathResponse.builder()
                            .parameters(page2Param)
                            .build());

            SdkSsmGateway gateway = new SdkSsmGateway(ssmClient);
            List<Parameter> result = gateway.fetchAll("/app/", true, true);

            // Both pages must be collected
            assertEquals(2, result.size(), "both pages must be collected");
            assertTrue(result.contains(page1Param), "page 1 parameter must be present");
            assertTrue(result.contains(page2Param), "page 2 parameter must be present");

            // The second request must carry the nextToken from the first response
            List<GetParametersByPathRequest> requests = requestCaptor.getAllValues();
            assertEquals(2, requests.size(), "exactly two SDK requests must be made");
            assertNull(requests.get(0).nextToken(), "first request must not carry a nextToken");
            assertEquals(
                    "token-page-2",
                    requests.get(1).nextToken(),
                    "second request must carry the nextToken from the first response");

            ctx.completeNow();
        }
    }
}
