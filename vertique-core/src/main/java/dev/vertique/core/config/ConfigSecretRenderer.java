// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Locale;

/**
 * Shared, array-aware redactor for log-safe rendering of config that may carry secrets.
 *
 * <p>Open property bags ({@link JsonObject}/{@link JsonArray} pass-through subtrees — config rule R9)
 * and credential-bearing URIs both routinely carry secrets ({@code sasl.password},
 * {@code ssl.keyStorePassword}, {@code basic.auth.user.info}, SFTP {@code user:pass@host} authority,
 * {@code …?password=…} query params). The framework keeps the live values intact for runtime use but
 * MUST never let a secret leak through a config record's {@code toString()}, an exception message, or
 * Jackson re-serialization of a debug dump. This class is the single definition of that redaction
 * rule, reused by every config {@code toString()} so the masking stays in lockstep across modules.
 *
 * <p>Two entry points cover the two shapes:
 * <ul>
 *   <li>{@link #redactBag(JsonObject)} — recurses into both {@link JsonObject} and {@link JsonArray}
 *       to any depth, masking each leaf whose full dotted path {@link #isSensitivePath(String) names a
 *       secret}.</li>
 *   <li>{@link #redactUri(String)} — masks the authority userinfo (the {@code user:pass} between
 *       {@code //} and {@code @}) and the value of any credential-bearing query parameter.</li>
 * </ul>
 *
 * <p>The secret-path predicate is the <em>union</em> of every token any module needs, so a bag
 * rendered through this redactor is masked consistently regardless of which module owns it. Besides
 * the dotted-substring tokens ({@code password}, {@code secret}, {@code token}, {@code passphrase},
 * {@code credential}, {@code jaas.config}, {@code user.info}, {@code private.key}, {@code keystore},
 * {@code truststore}), it also matches separator-normalized credential tokens ({@code apikey},
 * {@code accesskey}, {@code secretkey}) so {@code apiKey}/{@code api-key}/{@code api.key} and
 * {@code accessKey}/{@code access-key}/{@code access.key} are all masked regardless of casing or
 * separator.
 */
public final class ConfigSecretRenderer {

    /** The masked replacement rendered in place of a secret value. */
    public static final String MASK = "***";

    /**
     * The union of all secret tokens. A dotted config path is sensitive when (case-insensitively) it
     * contains any of these substrings. Kept as the single source of truth so every module's
     * redaction agrees.
     */
    private static final List<String> SECRET_TOKENS = List.of(
            "password",
            "secret",
            "token",
            "passphrase",
            "credential",
            "jaas.config",
            "user.info",
            "private.key",
            "keystore",
            "truststore");

    /**
     * Separator-normalized secret tokens. A path is also sensitive when its <em>normalized</em> form —
     * lower-cased with {@code .}, {@code -}, and {@code _} separators stripped — contains any of these.
     * This catches common credential key names that vary only by separator/casing convention so all of
     * {@code apiKey}, {@code api-key}, {@code api.key}, {@code accessKey}, {@code access-key},
     * {@code access.key}, {@code secretKey}, etc. match a single token regardless of how the operator
     * spelled them.
     */
    private static final List<String> NORMALIZED_SECRET_TOKENS = List.of("apikey", "accesskey", "secretkey");

    private ConfigSecretRenderer() {}

    /**
     * Returns {@code true} when the dotted config path names a secret value. The path is sensitive when
     * either:
     *
     * <ul>
     *   <li>its lower-cased form contains any of the union secret tokens ({@code password},
     *       {@code secret}, {@code token}, {@code passphrase}, {@code credential}, {@code jaas.config},
     *       {@code user.info}, {@code private.key}, {@code keystore}, {@code truststore}); or</li>
     *   <li>its <em>separator-normalized</em> form — lower-cased with {@code .}, {@code -}, and
     *       {@code _} stripped — contains any of the normalized credential tokens ({@code apikey},
     *       {@code accesskey}, {@code secretkey}), so that {@code apiKey}, {@code api-key},
     *       {@code api.key}, {@code accessKey}, {@code access-key}, and {@code access.key} all match
     *       regardless of separator/casing convention.</li>
     * </ul>
     *
     * <p>The match is a substring match in both forms, so a nested path such as
     * {@code ssl.trustStoreOptions.password} or {@code aws.access-key} is recognized at any depth.
     *
     * @param dottedPath the full dotted path of a config leaf (must not be {@code null})
     * @return {@code true} when the path names a credential/secret value
     */
    public static boolean isSensitivePath(String dottedPath) {
        String lower = dottedPath.toLowerCase(Locale.ROOT);
        if (SECRET_TOKENS.stream().anyMatch(lower::contains)) {
            return true;
        }
        String normalized = lower.replace(".", "").replace("-", "").replace("_", "");
        return NORMALIZED_SECRET_TOKENS.stream().anyMatch(normalized::contains);
    }

    /**
     * Renders an open property bag as a log-safe string, masking the value of every leaf whose full
     * dotted path {@link #isSensitivePath(String) names a secret}, at every depth.
     *
     * <p>The recursion descends into both nested {@link JsonObject}s and {@link JsonArray}s: a secret
     * nested under an array index (e.g. {@code items[0].password}) is masked just like a nested-object
     * secret, closing the gap where an array element object was previously copied verbatim. When a
     * nested object's own dotted path is sensitive, the whole sub-object is masked rather than
     * descended into. Array elements inherit their parent's dotted path (the index is not part of the
     * key shape the runtime flattens to), so a sensitive array key masks every element.
     *
     * <p>The input bag is never mutated — masking is applied to a deep copy used only for the returned
     * string. A {@code null} bag renders as {@code "null"}; an empty bag renders as {@code "{}"}.
     *
     * @param bag the property bag to render, or {@code null}
     * @return a string rendering of the bag with secret values masked at every depth, or
     *     {@code "null"} when the bag is {@code null}
     */
    public static String redactBag(JsonObject bag) {
        if (bag == null) {
            return "null";
        }
        return redactObject("", bag).encode();
    }

    /**
     * Recursively builds a masked copy of {@code obj}, accumulating the dotted path so
     * {@link #isSensitivePath(String)} sees the same key shape the runtime flattens to.
     *
     * @param prefix the dotted-key prefix accumulated so far (empty string at the root)
     * @param obj the current object node to mask
     * @return a new masked {@link JsonObject} mirroring {@code obj}'s structure
     */
    private static JsonObject redactObject(String prefix, JsonObject obj) {
        JsonObject masked = new JsonObject();
        for (String key : obj.fieldNames()) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = obj.getValue(key);
            if (isSensitivePath(fullKey)) {
                masked.put(key, MASK);
            } else if (value instanceof JsonObject nested) {
                masked.put(key, redactObject(fullKey, nested));
            } else if (value instanceof JsonArray array) {
                masked.put(key, redactArray(fullKey, array));
            } else {
                masked.put(key, value);
            }
        }
        return masked;
    }

    /**
     * Recursively builds a masked copy of {@code array}. Each element keeps the parent's dotted path
     * (the array index is not part of the key shape), so object/array elements are descended into and
     * scalar elements are copied verbatim (a sensitive array key is already handled by the caller,
     * which masks the whole value before recursing here).
     *
     * @param prefix the dotted-key prefix of the array (its owning key's full path)
     * @param array the current array node to mask
     * @return a new masked {@link JsonArray} mirroring {@code array}'s structure
     */
    private static JsonArray redactArray(String prefix, JsonArray array) {
        JsonArray masked = new JsonArray();
        for (Object value : array) {
            if (value instanceof JsonObject nested) {
                masked.add(redactObject(prefix, nested));
            } else if (value instanceof JsonArray nestedArray) {
                masked.add(redactArray(prefix, nestedArray));
            } else {
                masked.add(value);
            }
        }
        return masked;
    }

    /**
     * Returns a log-safe rendering of a URI with credentials masked: the authority <em>userinfo</em>
     * (the {@code user:pass} between {@code //} and {@code @}) and the value of any query parameter
     * whose name {@link #isSensitivePath(String) names a credential}. The scheme, host, port, and path
     * are kept intact for debuggability.
     *
     * <p>This is a best-effort string redaction that never throws: a {@code null} URI renders as
     * {@code "null"}; a URI with no scheme, no userinfo, or no query is handled gracefully. It does not
     * require a well-formed {@link java.net.URI} (Camel endpoint URIs frequently are not), so it
     * operates on the raw string rather than parsing.
     *
     * @param uri the URI to redact, or {@code null}
     * @return the URI with userinfo and credential query-param values masked, or {@code "null"} when
     *     {@code null}
     */
    public static String redactUri(String uri) {
        if (uri == null) {
            return "null";
        }
        return redactQuery(redactUserinfo(uri));
    }

    /**
     * Masks the authority userinfo of a URI: the {@code user:pass} (or bare {@code user}) between the
     * {@code //} that opens the authority and the {@code @} that ends it. The scheme, host, port, and
     * everything after the authority are left unchanged. A URI with no {@code //} authority or no
     * {@code @} in the authority is returned unchanged.
     *
     * <p>Per RFC 3986 §3.2.1, userinfo is the substring <em>before the last {@code @}</em> in the
     * authority component. When a password contains a literal (unencoded) {@code @} — e.g.
     * {@code sftp://user:p@ss@host/path} — the entire {@code user:p@ss} is still userinfo and must
     * be masked. Using {@code lastIndexOf} instead of {@code indexOf} handles this correctly while
     * remaining backward-compatible with the single-{@code @} case (last == first).
     *
     * @param uri the URI string (never {@code null})
     * @return the URI with any authority userinfo replaced by {@link #MASK}
     */
    private static String redactUserinfo(String uri) {
        int authorityStart = uri.indexOf("//");
        if (authorityStart < 0) {
            return uri;
        }
        int authorityFrom = authorityStart + 2;
        // The authority ends at the first '/', '?' or '#' after it.
        int authorityEnd = uri.length();
        for (int i = authorityFrom; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        // RFC 3986 §3.2.1: userinfo ends at the LAST '@' within the authority.
        // Using lastIndexOf ensures a raw '@' inside a password is treated as part of
        // the userinfo, not as the host separator.
        int at = uri.lastIndexOf('@', authorityEnd - 1);
        if (at < authorityFrom) {
            return uri; // no userinfo in this authority
        }
        return uri.substring(0, authorityFrom) + MASK + uri.substring(at);
    }

    /**
     * Masks the value of every credential-bearing query parameter in a URI. The query is the substring
     * after the first {@code ?}; each {@code &}-separated {@code name=value} pair whose name
     * {@link #isSensitivePath(String) names a credential} has its value replaced with {@link #MASK}.
     * A URI with no query string is returned unchanged.
     *
     * @param uri the URI string (never {@code null}; may already have userinfo masked)
     * @return the URI with credential query-param values masked
     */
    private static String redactQuery(String uri) {
        int queryStart = uri.indexOf('?');
        if (queryStart < 0) {
            return uri;
        }
        String base = uri.substring(0, queryStart);
        String query = uri.substring(queryStart + 1);
        StringBuilder redacted = new StringBuilder(base).append('?');
        String[] params = query.split("&", -1);
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                redacted.append('&');
            }
            String param = params[i];
            int eq = param.indexOf('=');
            if (eq >= 0 && isSensitivePath(param.substring(0, eq))) {
                redacted.append(param, 0, eq + 1).append(MASK);
            } else {
                redacted.append(param);
            }
        }
        return redacted.toString();
    }
}
