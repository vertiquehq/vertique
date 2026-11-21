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
        @DisplayName("matches privateKeyPem/private-key/private_key through the normalized path")
        void privateKeyVariantsMatch() {
            assertTrue(ConfigSecretRenderer.isSensitivePath("privateKeyPem"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("blob.cdn.cloudfront.privateKeyPem"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("private-key"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("private_key"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("PRIVATE_KEY_PEM"));
        }

        @Test
        @DisplayName("matches camel-case and hyphenated jaasConfig and userInfo through the normalized path")
        void jaasConfigAndUserInfoVariantsMatch() {
            assertTrue(ConfigSecretRenderer.isSensitivePath("jaasConfig"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("sasl.jaas-config"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("kafka.JAAS_CONFIG"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("userInfo"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("user_info"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("basic.auth.user-info"));
            // the dotted forms keep matching
            assertTrue(ConfigSecretRenderer.isSensitivePath("sasl.jaas.config"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("basic.auth.user.info"));
        }

        @Test
        @DisplayName("near misses of jaasconfig and userinfo stay non-secret")
        void jaasConfigAndUserInfoNearMissesDoNotMatch() {
            assertFalse(ConfigSecretRenderer.isSensitivePath("jaas"));
            assertFalse(ConfigSecretRenderer.isSensitivePath("jaasMode"));
            assertFalse(ConfigSecretRenderer.isSensitivePath("user"));
            assertFalse(ConfigSecretRenderer.isSensitivePath("userId"));
            assertFalse(ConfigSecretRenderer.isSensitivePath("jaas.mode"));
        }

        @Test
        @DisplayName("returns false for a non-secret path")
        void nonSecretPath() {
            assertFalse(ConfigSecretRenderer.isSensitivePath("bootstrap.servers"));
            assertFalse(ConfigSecretRenderer.isSensitivePath("max.poll.records"));
            assertFalse(ConfigSecretRenderer.isSensitivePath("url"));
        }
    }

    // --- isUriPath: the URL-key predicate ---

    @Nested
    @DisplayName("isUriPath")
    class UriPath {

        @Test
        @DisplayName("matches url, uri, endpoint and connection-string names through the normalized path")
        void matchesUrlNames() {
            assertTrue(ConfigSecretRenderer.isUriPath("jdbcUrl"));
            assertTrue(ConfigSecretRenderer.isUriPath("restClient.orders.baseUrl"));
            assertTrue(ConfigSecretRenderer.isUriPath("redis.endpoints"));
            assertTrue(ConfigSecretRenderer.isUriPath("camel.uri"));
            assertTrue(ConfigSecretRenderer.isUriPath("sql.connection-string"));
            assertTrue(ConfigSecretRenderer.isUriPath("sql.connection_string"));
            assertTrue(ConfigSecretRenderer.isUriPath("s3.endpoint"));
            assertTrue(ConfigSecretRenderer.isUriPath("JDBC_URL"));
        }

        @Test
        @DisplayName(
                "matches a look-alike such as security (contains uri) by design: redactUri leaves a plain string equal")
        void lookAlikeMatches() {
            assertTrue(ConfigSecretRenderer.isUriPath("security.audience"));
            assertEquals("orders-api", ConfigSecretRenderer.redactUri("orders-api"));
        }

        @Test
        @DisplayName("returns false for a path without a URL-naming token")
        void nonUrlPath() {
            assertFalse(ConfigSecretRenderer.isUriPath("host"));
            assertFalse(ConfigSecretRenderer.isUriPath("bootstrap.servers"));
            assertFalse(ConfigSecretRenderer.isUriPath("vault.address"));
            assertFalse(ConfigSecretRenderer.isUriPath("password"));
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
        @DisplayName("masks a privateKeyPem leaf in a bag")
        void privateKeyPemScrubbed() {
            JsonObject bag = new JsonObject()
                    .put("privateKeyPem", "-----BEGIN PRIVATE KEY-----PEM1")
                    .put("private-key", "PEM2")
                    .put("keyPairId", "K123");

            String rendered = ConfigSecretRenderer.redactBag(bag);

            assertFalse(rendered.contains("PEM1"), "privateKeyPem value must be masked");
            assertFalse(rendered.contains("PEM2"), "private-key value must be masked");
            assertTrue(rendered.contains("K123"), "non-secret value stays visible");
            assertTrue(rendered.contains(ConfigSecretRenderer.MASK), "mask token present");
        }

        @Test
        @DisplayName("renders a credentialed URL at a URL-named path through redactUri, like the mapper dump")
        void urlLeafRedacted() {
            JsonObject bag = new JsonObject()
                    .put("jdbcUrl", "jdbc:postgresql://u:URLSEKRIT@db/app?password=QSEKRIT&ssl=true")
                    .put("address", "sftp://u:ADDRSEKRIT@host/in")
                    .put("nested", new JsonObject().put("baseUrl", "https://u:NESTSEKRIT@host/p"))
                    .put(
                            "endpoints",
                            new JsonArray().add("redis://u:ARRSEKRIT@h1:6379").add("redis://h2:6379"));

            String rendered = ConfigSecretRenderer.redactBag(bag);

            assertFalse(rendered.contains("URLSEKRIT"), rendered);
            assertFalse(rendered.contains("QSEKRIT"), rendered);
            assertFalse(rendered.contains("NESTSEKRIT"), rendered);
            assertFalse(rendered.contains("ARRSEKRIT"), rendered);
            assertTrue(rendered.contains("jdbc:postgresql://***@db/app?password=***&ssl=true"), rendered);
            assertTrue(rendered.contains("redis://h2:6379"), "a URL without credentials stays as given");
            assertTrue(rendered.contains("ADDRSEKRIT"), "a path that does not name a URL is copied as given");
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
        @DisplayName("treats ';' as a parameter separator, with or without a '?'")
        void semicolonSeparatesParameters() {
            assertEquals(
                    "jdbc:sqlserver://h:1433;databaseName=app;user=sa;password=***;encrypt=true",
                    ConfigSecretRenderer.redactUri(
                            "jdbc:sqlserver://h:1433;databaseName=app;user=sa;password=SEKRIT;encrypt=true"));
            assertEquals(
                    "jdbc:mysql://h/db?useSSL=true;password=***&x=1",
                    ConfigSecretRenderer.redactUri("jdbc:mysql://h/db?useSSL=true;password=SEKRIT&x=1"));
            assertEquals("https://h/p;jsessionid=abc", ConfigSecretRenderer.redactUri("https://h/p;jsessionid=abc"));
        }

        @Test
        @DisplayName("masks pwd, passwd and sig as whole parameter names only")
        void shortCredentialNamesMasked() {
            assertEquals(
                    "x://h?pwd=***&PASSWD=***&sig=***&design=a&signal=b",
                    ConfigSecretRenderer.redactUri("x://h?pwd=1&PASSWD=2&sig=3&design=a&signal=b"));
            assertTrue(ConfigSecretRenderer.isSensitivePath("password"));
            assertFalse(ConfigSecretRenderer.isSensitivePath("sig"), "the short names widen the URI rule only");
        }

        @Test
        @DisplayName("masks the user/pass of an Oracle thin URL and leaves a credential-free one alone")
        void oracleThinCredentialsMasked() {
            assertEquals(
                    "jdbc:oracle:thin:***@//h:1521/svc",
                    ConfigSecretRenderer.redactUri("jdbc:oracle:thin:scott/TIGER@//h:1521/svc"));
            assertEquals(
                    "jdbc:oracle:thin:***@h:1521:orcl",
                    ConfigSecretRenderer.redactUri("jdbc:oracle:thin:scott/TIGER@h:1521:orcl"));
            assertEquals(
                    "jdbc:oracle:thin:@//h:1521/svc", ConfigSecretRenderer.redactUri("jdbc:oracle:thin:@//h:1521/svc"));
        }

        @Test
        @DisplayName("masks a password that contains '@' whole in a semicolon-style URL, keeping host and user")
        void atSignInSemicolonPasswordMasked() {
            assertEquals(
                    "jdbc:sqlserver://host:1433;user=u;password=***;encrypt=true",
                    ConfigSecretRenderer.redactUri("jdbc:sqlserver://host:1433;user=u;password=p@ss;encrypt=true"));
            assertEquals(
                    "jdbc:sqlserver://host:1433;databaseName=x;password=***",
                    ConfigSecretRenderer.redactUri("jdbc:sqlserver://host:1433;databaseName=x;password=p@ss"));
            assertEquals("http://h;password=***", ConfigSecretRenderer.redactUri("http://h;password=p@ss"));
        }

        @Test
        @DisplayName("masks query credentials that follow a ';' path parameter")
        void queryAfterPathParameterMasked() {
            assertEquals(
                    "http://h/p;jsessionid=1?password=***",
                    ConfigSecretRenderer.redactUri("http://h/p;jsessionid=1?password=secret"));
            assertEquals(
                    "http://h/p;v=1?a=b&token=***", ConfigSecretRenderer.redactUri("http://h/p;v=1?a=b&token=secret"));
        }

        @Test
        @DisplayName("an Oracle thin password that contains '@' is masked whole")
        void oracleAtSignInPasswordMasked() {
            assertEquals(
                    "jdbc:oracle:thin:***@//host:1521/svc",
                    ConfigSecretRenderer.redactUri("jdbc:oracle:thin:scott/p@ss@//host:1521/svc"));
            assertEquals(
                    "jdbc:oracle:thin:***@host:1521:orcl",
                    ConfigSecretRenderer.redactUri("jdbc:oracle:thin:scott/p@ss@host:1521:orcl"));
        }

        @Test
        @DisplayName("an Oracle thin URL without credentials is not altered by a later '@'")
        void oracleWithoutCredentialsUnchanged() {
            assertEquals(
                    "jdbc:oracle:thin:@host:1521/a@b",
                    ConfigSecretRenderer.redactUri("jdbc:oracle:thin:@host:1521/a@b"));
            assertEquals(
                    "jdbc:oracle:thin:@host:1521/svc?redirect=a@//b",
                    ConfigSecretRenderer.redactUri("jdbc:oracle:thin:@host:1521/svc?redirect=a@//b"));
        }

        @Test
        @DisplayName("a RAW value ends at a ';' too, and an unclosed RAW on a non-credential parameter hides nothing")
        void rawValueBoundaries() {
            assertEquals(
                    "jdbc:x://h;a=RAW(x);password=***",
                    ConfigSecretRenderer.redactUri("jdbc:x://h;a=RAW(x);password=secret"));
            assertEquals("x://h?a=RAW(x&password=***", ConfigSecretRenderer.redactUri("x://h?a=RAW(x&password=s"));
        }

        @Test
        @DisplayName("masks a Camel RAW value whole, even when it contains a separator")
        void camelRawValueMasked() {
            assertEquals(
                    "ftp://h/in?username=a&password=***&delay=5",
                    ConfigSecretRenderer.redactUri("ftp://h/in?username=a&password=RAW(se&c;ret)&delay=5"));
            assertEquals(
                    "ftp://h/in?password=***", ConfigSecretRenderer.redactUri("ftp://h/in?password=RAW{se)&c;ret}"));
            assertEquals(
                    "ftp://h/in?password=***", ConfigSecretRenderer.redactUri("ftp://h/in?password=RAW(unclosed&x"));
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
