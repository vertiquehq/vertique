// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigSecretRenderer} — the shared, array-aware redactor used by every
 * config {@code toString()} that renders an open property bag or a credential-bearing URI.
 *
 * <p>Covers: the union secret-path predicate over every token; flat- and nested-object recursion;
 * recursion into {@link JsonArray} element objects (the W2 gap — a value nested under an array index
 * leaked verbatim before this redactor); URI authority-userinfo masking (the W1 gap —
 * {@code user:pass@host} leaked {@code pass}); URI credential query-param masking; and the
 * non-mutation / null-safety guarantees.
 */
@DisplayName("ConfigSecretRenderer")
class ConfigSecretRendererTest {

    // --- isSensitivePath: the union predicate ---

    @Nested
    @DisplayName("isSensitivePath")
    class SensitivePath {

        @Test
        @DisplayName("matches every union secret token, case-insensitively, as a substring of the dotted path")
        void matchesEveryUnionToken() {
            // One representative dotted path per union token.
            assertTrue(ConfigSecretRenderer.isSensitivePath("sasl.password"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("basic.auth.credentials.secret"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("access.token"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("ssl.passphrase"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("client.credential"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("sasl.jaas.config"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("basic.auth.user.info"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("ssl.private.key"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("ssl.keystore.location"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("ssl.truststore.location"));

            // Case-insensitive: uppercased path still matches.
            assertTrue(ConfigSecretRenderer.isSensitivePath("SASL.PASSWORD"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("KeyStore.Location"));
        }

        @Test
        @DisplayName("returns false for a non-secret path")
        void nonSecretPath() {
            assertFalse(ConfigSecretRenderer.isSensitivePath("bootstrap.servers"));
            assertFalse(ConfigSecretRenderer.isSensitivePath("max.poll.records"));
            assertFalse(ConfigSecretRenderer.isSensitivePath("url"));
        }
    }

    // --- redactBag: object recursion ---

    @Nested
    @DisplayName("redactBag — object recursion")
    class ObjectRecursion {

        @Test
        @DisplayName("masks a flat secret leaf, keeps non-secret leaves")
        void flatSecretMasked() {
            JsonObject bag = new JsonObject().put("sasl.password", "SEKRIT").put("bootstrap.servers", "broker:9092");

            String rendered = ConfigSecretRenderer.redactBag(bag);

            assertFalse(rendered.contains("SEKRIT"), "flat secret must be masked");
            assertTrue(rendered.contains("broker:9092"), "non-secret stays visible");
            assertTrue(rendered.contains(ConfigSecretRenderer.MASK), "mask token present");
        }

        @Test
        @DisplayName("masks a nested secret leaf by its full dotted path")
        void nestedSecretMaskedByDottedPath() {
            JsonObject bag = new JsonObject()
                    .put("sasl", new JsonObject().put("password", "SEKRIT").put("mechanism", "PLAIN"));

            String rendered = ConfigSecretRenderer.redactBag(bag);

            assertFalse(rendered.contains("SEKRIT"), "nested sasl.password must be masked");
            assertTrue(rendered.contains("PLAIN"), "non-secret nested scalar stays visible");
        }

        @Test
        @DisplayName("masks apiKey/api-key/api.key variants in a bag")
        void apiKeyVariantsScrubbed() {
            JsonObject bag = new JsonObject()
                    .put("apiKey", "AKEY1")
                    .put("api-key", "AKEY2")
                    .put("api.key", "AKEY3")
                    .put("bootstrap.servers", "broker:9092");

            String rendered = ConfigSecretRenderer.redactBag(bag);

            assertFalse(rendered.contains("AKEY1"), "apiKey value must be masked");
            assertFalse(rendered.contains("AKEY2"), "api-key value must be masked");
            assertFalse(rendered.contains("AKEY3"), "api.key value must be masked");
            assertTrue(rendered.contains("broker:9092"), "non-secret value stays visible");
            assertTrue(rendered.contains(ConfigSecretRenderer.MASK), "mask token present");
        }

        @Test
        @DisplayName("masks accessKey/access-key/access.key variants in a bag")
        void accessKeyVariantsScrubbed() {
            JsonObject bag = new JsonObject()
                    .put("accessKey", "ACC1")
                    .put("access-key", "ACC2")
                    .put("access.key", "ACC3")
                    .put("bootstrap.servers", "broker:9092");

            String rendered = ConfigSecretRenderer.redactBag(bag);

            assertFalse(rendered.contains("ACC1"), "accessKey value must be masked");
            assertFalse(rendered.contains("ACC2"), "access-key value must be masked");
            assertFalse(rendered.contains("ACC3"), "access.key value must be masked");
            assertTrue(rendered.contains("broker:9092"), "non-secret value stays visible");
            assertTrue(rendered.contains(ConfigSecretRenderer.MASK), "mask token present");
        }

        @Test
        @DisplayName("does not mutate the input bag")
        void inputNotMutated() {
            JsonObject bag = new JsonObject().put("sasl.password", "SEKRIT");

            ConfigSecretRenderer.redactBag(bag);

            assertEquals("SEKRIT", bag.getString("sasl.password"), "input bag must be untouched");
        }

        @Test
        @DisplayName("null bag renders as the literal null, empty bag is safe")
        void nullAndEmptySafe() {
            assertEquals("null", ConfigSecretRenderer.redactBag(null));
            assertEquals("{}", ConfigSecretRenderer.redactBag(new JsonObject()));
        }
    }

    // --- redactBag: array recursion (the W2 gap) ---

    @Nested
    @DisplayName("redactBag — array recursion")
    class ArrayRecursion {

        @Test
        @DisplayName("masks a secret inside an object nested under an array index")
        void arrayElementObjectSecretMasked() {
            JsonObject bag = new JsonObject()
                    .put(
                            "items",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put("password", "SEKRIT")
                                            .put("name", "n1")));

            String rendered = ConfigSecretRenderer.redactBag(bag);

            assertFalse(rendered.contains("SEKRIT"), "array-nested password must be masked (W2)");
            assertTrue(rendered.contains("n1"), "non-secret array-nested value stays visible");
        }

        @Test
        @DisplayName("masks a secret nested deeper than one array level")
        void deeplyNestedArraySecretMasked() {
            JsonObject bag = new JsonObject()
                    .put(
                            "outer",
                            new JsonArray()
                                    .add(new JsonObject()
                                            .put(
                                                    "inner",
                                                    new JsonArray().add(new JsonObject().put("token", "DEEPSEKRIT")))));

            String rendered = ConfigSecretRenderer.redactBag(bag);

            assertFalse(rendered.contains("DEEPSEKRIT"), "deeply array-nested token must be masked");
        }
    }

    // --- redactUri ---

    @Nested
    @DisplayName("redactUri")
    class UriRedaction {

        @Test
        @DisplayName("masks the authority userinfo (user:pass@host), keeping scheme/host/path (W1)")
        void userinfoMasked() {
            String redacted = ConfigSecretRenderer.redactUri("sftp://user:SEKRIT@host:22/path");

            assertFalse(redacted.contains("SEKRIT"), "userinfo password must be masked (W1)");
            assertTrue(redacted.contains("sftp"), "scheme stays visible");
            assertTrue(redacted.contains("host"), "host stays visible");
            assertTrue(redacted.contains("/path"), "path stays visible");
        }

        @Test
        @DisplayName("masks a credential query-param value, keeps non-credential params")
        void credentialQueryParamMasked() {
            String redacted = ConfigSecretRenderer.redactUri("sftp://host/in?username=alice&password=SEKRIT");

            assertFalse(redacted.contains("SEKRIT"), "password query-param value must be masked");
            assertTrue(redacted.contains("alice"), "non-credential query param stays visible");
        }

        @Test
        @DisplayName("masks both userinfo and credential query params together")
        void userinfoAndQueryMasked() {
            String redacted = ConfigSecretRenderer.redactUri("https://u:PASSSEKRIT@host/p?token=QSEKRIT&q=ok");

            assertFalse(redacted.contains("PASSSEKRIT"), "userinfo must be masked");
            assertFalse(redacted.contains("QSEKRIT"), "credential query param must be masked");
            assertTrue(redacted.contains("q=ok"), "non-credential query param stays visible");
        }

        @Test
        @DisplayName("masks ENTIRE userinfo when password contains a raw unencoded '@' (last-@ rule)")
        void userinfoWithRawAtFullyMasked() {
            // sftp://user:p@ss@host/path — the password itself contains a literal '@'.
            // RFC 3986 §3.2.1: userinfo ends at the LAST '@' before the host, so the
            // entire 'user:p@ss' is userinfo and must be masked. The host/path must be intact.
            String redacted = ConfigSecretRenderer.redactUri("sftp://user:p@ss@host/path");

            assertFalse(redacted.contains("p@ss"), "partial password tail must not leak");
            assertFalse(redacted.contains("ss"), "password tail 'ss' must not leak");
            assertTrue(redacted.contains("host"), "host must remain visible");
            assertTrue(redacted.contains("/path"), "path must remain visible");
        }

        @Test
        @DisplayName("URI with no scheme, no userinfo, no query is returned unchanged")
        void plainUriUnchanged() {
            assertEquals("sftp://host/in", ConfigSecretRenderer.redactUri("sftp://host/in"));
        }

        @Test
        @DisplayName("null URI renders as the literal null and never throws")
        void nullUriSafe() {
            assertEquals("null", ConfigSecretRenderer.redactUri(null));
        }
    }
}
