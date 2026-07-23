// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TagPolicyValidator} — verifies tag-key format rules, reserved-key
 * rejections, credential-shape key rejections via whole-segment matching, value-length and
 * credential-shape value rejections, and the "no secret values in exception messages" invariant.
 *
 * <p>These tests cover every validation rule in plan D-M rev 5, including the nuanced
 * segment-semantics tests whose names call out whole-segment vs. substring behaviour.
 */
class TagPolicyValidatorTest {

    // --- Helpers ---

    /**
     * Builds a {@link MetricsConfig.TagsConfig} with the given service name and extra tags,
     * using the standard Jackson deserialization path.
     *
     * @param service the service tag value (may be {@code null})
     * @param extra   the extra tags map
     * @return a deserialized config
     */
    private static MetricsConfig.TagsConfig tagsConfig(String service, Map<String, String> extra) {
        JsonObject extraJson = new JsonObject();
        if (extra != null) {
            extra.forEach(extraJson::put);
        }
        JsonObject json = new JsonObject().put("extra", extraJson);
        if (service != null) {
            json.put("service", service);
        }
        return json.mapTo(MetricsConfig.TagsConfig.class);
    }

    /**
     * Builds a {@link MetricsConfig.TagsConfig} with no service name and the given extra tags.
     *
     * @param extra the extra tags map
     * @return a deserialized config
     */
    private static MetricsConfig.TagsConfig tagsConfig(Map<String, String> extra) {
        return tagsConfig(null, extra);
    }

    // --- Happy path ---

    @Nested
    class HappyPath {

        @Test
        @DisplayName("valid service + valid extras: orders-api, env=prod, region=eu-west-1, team=platform -> no throw")
        void validServiceAndExtras() {
            Map<String, String> extra = Map.of("env", "prod", "region", "eu-west-1", "team", "platform");
            MetricsConfig.TagsConfig tags = tagsConfig("orders-api", extra);
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tags));
        }

        @Test
        @DisplayName("null service and empty extras -> no throw")
        void nullServiceEmptyExtras() {
            MetricsConfig.TagsConfig tags = tagsConfig(null, Collections.emptyMap());
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tags));
        }

        @Test
        @DisplayName("key with dots: build.version -> accepted (valid format)")
        void keyWithDots() {
            Map<String, String> extra = Map.of("build.version", "1.0.0");
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(extra)));
        }

        @Test
        @DisplayName("key with hyphens: git-sha -> accepted (valid format)")
        void keyWithHyphens() {
            Map<String, String> extra = Map.of("git-sha", "abc123");
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(extra)));
        }
    }

    // --- Rule 1: extra map size limit ---

    @Nested
    class ExtraMapSizeLimit {

        @Test
        @DisplayName("17 extra entries -> throws ConfigurationException mentioning count rule")
        void seventeenExtrasThrows() {
            Map<String, String> extra = new HashMap<>();
            for (int i = 1; i <= 17; i++) {
                extra.put("key" + i, "val");
            }
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> TagPolicyValidator.validate(tagsConfig(extra)));
            assertTrue(
                    ex.getMessage().contains("16"),
                    "Exception message must mention the count rule (max 16), got: " + ex.getMessage());
        }

        @Test
        @DisplayName("exactly 16 extra entries -> no throw")
        void sixteenExtrasAccepted() {
            Map<String, String> extra = new HashMap<>();
            for (int i = 1; i <= 16; i++) {
                extra.put("key" + i, "val");
            }
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(extra)));
        }
    }

    // --- Rule 2 & 3: key format and reserved keys ---

    @Nested
    class KeyFormatAndReservedKeys {

        @Test
        @DisplayName("uppercase key 'Env' -> throws (pattern requires lowercase start)")
        void uppercaseKeyThrows() {
            assertThrows(
                    ConfigurationException.class, () -> TagPolicyValidator.validate(tagsConfig(Map.of("Env", "prod"))));
        }

        @Test
        @DisplayName("key starting with digit '9region' -> throws (pattern requires [a-z] start)")
        void digitStartKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("9region", "us-east-1"))));
        }

        @Test
        @DisplayName("65-char key -> throws (max length 64)")
        void tooLongKeyThrows() {
            // key must satisfy [a-z][a-z0-9._-]{0,63} -> max total 64 chars
            String key = "a" + "b".repeat(64); // 65 chars total
            assertThrows(
                    ConfigurationException.class, () -> TagPolicyValidator.validate(tagsConfig(Map.of(key, "val"))));
        }

        @Test
        @DisplayName("64-char key -> accepted (exactly at max length)")
        void maxLengthKeyAccepted() {
            String key = "a" + "b".repeat(63); // 64 chars total
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(Map.of(key, "val"))));
        }

        @Test
        @DisplayName("key 'service' -> throws (reserved key)")
        void serviceKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("service", "my-service"))));
        }

        @Test
        @DisplayName("key 'vertique.custom' -> throws (reserved prefix)")
        void vertiqueCustomKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("vertique.custom", "val"))));
        }

        @Test
        @DisplayName("key 'method' -> throws (collision with GUARDED_TAG_KEYS)")
        void methodKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("method", "GET"))));
        }

        @Test
        @DisplayName("key 'route' -> throws (collision with GUARDED_TAG_KEYS)")
        void routeKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("route", "/api/v1"))));
        }
    }

    // --- Rule 4: segment-based secret-like key rejection ---

    @Nested
    class SegmentMatchingKeyRejection {

        @Test
        @DisplayName("key 'auth_token' -> throws: segment 'token' is a credential indicator")
        void authTokenKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("auth_token", "val"))));
        }

        @Test
        @DisplayName("key 'password' -> throws: whole segment equals 'password'")
        void passwordKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("password", "val"))));
        }

        @Test
        @DisplayName("key 'db_secret' -> throws: segment 'secret' is a credential indicator")
        void dbSecretKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("db_secret", "val"))));
        }

        @Test
        @DisplayName("key 'apikey' -> throws: whole segment 'apikey' is a credential indicator")
        void apikeyKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("apikey", "val"))));
        }

        @Test
        @DisplayName("key 'api_key_version' -> throws: adjacent segments 'api'+'key' form a credential pair")
        void apiKeyVersionThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("api_key_version", "val"))));
        }

        @Test
        @DisplayName("key 'my-credential' -> throws: segment 'credential' is a credential indicator")
        void myCredentialKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("my-credential", "val"))));
        }

        @Test
        @DisplayName("WHOLE-SEGMENT SEMANTICS: key 'tokenizer_model' -> ACCEPTED: segments 'tokenizer','model' "
                + "are not credential indicators (whole-segment match, not substring)")
        void tokenizerModelKeyAccepted() {
            // 'tokenizer' != 'token'; whole-segment match only
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(Map.of("tokenizer_model", "val"))));
        }

        @Test
        @DisplayName("WHOLE-SEGMENT SEMANTICS: key 'secretariat_office' -> ACCEPTED: segment 'secretariat' "
                + "!= 'secret' (whole-segment match, not substring)")
        void secretariatOfficeKeyAccepted() {
            // 'secretariat' != 'secret'; whole-segment match only
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(Map.of("secretariat_office", "val"))));
        }

        @Test
        @DisplayName("WHOLE-SEGMENT SEMANTICS: key 'passwordless' -> ACCEPTED: single segment 'passwordless' "
                + "!= 'password' (whole-segment match, not substring)")
        void passwordlessKeyAccepted() {
            // 'passwordless' != 'password'; whole-segment match only
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(Map.of("passwordless", "val"))));
        }

        @Test
        @DisplayName("key 'token_bucket' -> throws: segment 'token' is a credential indicator")
        void tokenBucketThrows() {
            // 'token' == 'token'; whole-segment match, must throw
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("token_bucket", "val"))));
        }

        @Test
        @DisplayName("key 'db.passwd' -> throws: segment 'passwd' is a credential indicator")
        void dbPasswdKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("db.passwd", "val"))));
        }

        // --- Expanded denylist (fix #8) ---

        @Test
        @DisplayName("key 'authorization' -> throws: whole segment 'authorization' is a credential indicator")
        void authorizationKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("authorization", "val"))));
        }

        @Test
        @DisplayName("key 'bearer' -> throws: whole segment 'bearer' is a credential indicator")
        void bearerKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("bearer", "val"))));
        }

        @Test
        @DisplayName("key 'app.bearer.token' -> throws: segment 'bearer' is a credential indicator")
        void appBearerTokenKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("app.bearer.token", "val"))));
        }

        @Test
        @DisplayName("key 'accesskey' -> throws: whole segment 'accesskey' is a credential indicator")
        void accesskeyKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("accesskey", "val"))));
        }

        @Test
        @DisplayName("key 'privatekey' -> throws: whole segment 'privatekey' is a credential indicator")
        void privatekeyKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("privatekey", "val"))));
        }

        @Test
        @DisplayName("key 'access_key' -> throws: adjacent segments 'access'+'key' form a credential pair")
        void accessKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("access_key", "val"))));
        }

        @Test
        @DisplayName("key 'private-key' -> throws: adjacent segments 'private'+'key' form a credential pair")
        void privateKeyThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("private-key", "val"))));
        }

        @Test
        @DisplayName("WHOLE-SEGMENT SEMANTICS: key 'access_count' -> ACCEPTED: "
                + "segment 'access' alone is not a credential indicator (only access+key adjacency is rejected)")
        void accessCountKeyAccepted() {
            // 'access' alone is not rejected — only adjacent ('access','key') pair is
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(Map.of("access_count", "val"))));
        }

        @Test
        @DisplayName("WHOLE-SEGMENT SEMANTICS: key 'privatedata' -> ACCEPTED: "
                + "single segment 'privatedata' != 'privatekey' (whole-segment match)")
        void privatedataKeyAccepted() {
            // 'privatedata' is a single segment that doesn't match any credential indicator
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(Map.of("privatedata", "val"))));
        }
    }

    // --- Rule 5: value validations ---

    @Nested
    class ValueValidation {

        @Test
        @DisplayName("257-char value -> throws; exception message does NOT contain the value")
        void tooLongValueThrowsWithoutLeakingValue() {
            String longValue = "x".repeat(257);
            String key = "env";
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of(key, longValue))));
            // message must not contain the overlong value
            assertFalse(ex.getMessage().contains(longValue), "Exception message must not contain the overlong value");
            // message should name the offending key
            assertTrue(ex.getMessage().contains(key), "Exception message must name the offending key 'env'");
        }

        @Test
        @DisplayName("256-char value -> accepted (at the max-length boundary)")
        void maxLengthValueAccepted() {
            String value = "x".repeat(256);
            assertDoesNotThrow(() -> TagPolicyValidator.validate(tagsConfig(Map.of("env", value))));
        }

        @Test
        @DisplayName("value starting with 'eyJ' -> throws (JWT token shape)")
        void jwtValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "eyJhbGciOiJSUzI1NiJ9"))));
        }

        @Test
        @DisplayName("value starting with 'AKIA' -> throws (AWS key shape)")
        void awsKeyValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "AKIAIOSFODNN7EXAMPLE"))));
        }

        @Test
        @DisplayName("value starting with 'ghp_' -> throws (GitHub personal token shape)")
        void githubTokenValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "ghp_abc123xxxxxxxx"))));
        }

        @Test
        @DisplayName("value starting with 'xoxb-' -> throws (Slack bot token shape)")
        void slackBotTokenValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "xoxb-12345-abcde"))));
        }

        @Test
        @DisplayName("value starting with 'xoxp-' -> throws (Slack user token shape)")
        void slackUserTokenValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "xoxp-9999-zzzzz"))));
        }

        @Test
        @DisplayName("service value starting with 'eyJ' -> throws (JWT token shape in service field)")
        void serviceValueJwtThrows() {
            MetricsConfig.TagsConfig tags = tagsConfig("eyJhbGciOiJSUzI1NiJ9.payload", Collections.emptyMap());
            assertThrows(ConfigurationException.class, () -> TagPolicyValidator.validate(tags));
        }

        // --- HTTP auth header value shapes (fix #8) ---

        @Test
        @DisplayName("value starting with 'Bearer ' (case-sensitive) -> throws (HTTP Authorization header value)")
        void bearerValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "Bearer abc123token"))));
        }

        @Test
        @DisplayName("value starting with 'bearer ' (lowercase) -> throws (HTTP auth header value, case-insensitive)")
        void bearerLowercaseValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "bearer abc123token"))));
        }

        @Test
        @DisplayName("value starting with 'BEARER ' (uppercase) -> throws (HTTP auth header value, case-insensitive)")
        void bearerUppercaseValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "BEARER abc123token"))));
        }

        @Test
        @DisplayName("value starting with 'Basic ' (case-sensitive) -> throws (HTTP Basic auth header value)")
        void basicValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "Basic dXNlcjpwYXNz"))));
        }

        @Test
        @DisplayName(
                "value starting with 'basic ' (lowercase) -> throws (HTTP Basic auth header value, case-insensitive)")
        void basicLowercaseValueThrows() {
            assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", "basic dXNlcjpwYXNz"))));
        }

        @Test
        @DisplayName("sentinel absence for Bearer value: exception message contains no part of credential value")
        void bearerValueSentinelAbsent() {
            String credential = "Bearer SENTINEL_SECRET_TOKEN_VALUE";
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", credential))));
            assertFalse(
                    ex.getMessage().contains("SENTINEL_SECRET_TOKEN_VALUE"),
                    "Exception message must not contain the credential value, got: " + ex.getMessage());
            assertFalse(
                    ex.getMessage().contains(credential),
                    "Exception message must not contain the full credential string, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("sentinel absence for Basic value: exception message contains no part of credential value")
        void basicValueSentinelAbsent() {
            String credential = "Basic SENTINEL_SECRET_TOKEN_VALUE";
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class,
                    () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", credential))));
            assertFalse(
                    ex.getMessage().contains("SENTINEL_SECRET_TOKEN_VALUE"),
                    "Exception message must not contain the credential value, got: " + ex.getMessage());
        }
    }

    // --- Sentinel / value-not-in-message invariant ---

    @Nested
    class NoSecretValueInExceptionMessage {

        /**
         * The sentinel string represents a value that could be a real secret. All exception paths
         * for value violations must ensure the sentinel is absent from the exception message and its
         * toString representation.
         */
        private static final String SENTINEL = "SENTINEL_SECRET_VALUE";

        @Test
        @DisplayName("oversize value under sentinel key: exception message and toString contain no sentinel")
        void oversizeValueSentinelAbsent() {
            // Use an oversize value that starts with the sentinel
            String value = SENTINEL + "x".repeat(240); // > 256 chars
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class, () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", value))));

            String msg = ex.getMessage();
            String str = ex.toString();
            assertFalse(msg.contains(SENTINEL), "Exception message must not contain sentinel, got: " + msg);
            assertFalse(str.contains(SENTINEL), "Exception toString must not contain sentinel, got: " + str);
        }

        @Test
        @DisplayName("credential-shape value under sentinel key: exception message contains no sentinel")
        void credentialShapeValueSentinelAbsent() {
            // Construct a value that passes length but starts with 'eyJ' and contains the sentinel
            String value = "eyJ" + SENTINEL;
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class, () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", value))));

            String msg = ex.getMessage();
            assertFalse(msg.contains(SENTINEL), "Exception message must not contain sentinel, got: " + msg);
            assertFalse(
                    msg.contains(value),
                    "Exception message must not contain ANY part of the credential value, got: " + msg);
        }

        @Test
        @DisplayName("AKIA-prefix value under sentinel key: exception message contains no sentinel")
        void akiaValueSentinelAbsent() {
            String value = "AKIA" + SENTINEL;
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class, () -> TagPolicyValidator.validate(tagsConfig(Map.of("env", value))));

            assertFalse(
                    ex.getMessage().contains(SENTINEL),
                    "Exception message must not contain sentinel, got: " + ex.getMessage());
        }
    }
}
