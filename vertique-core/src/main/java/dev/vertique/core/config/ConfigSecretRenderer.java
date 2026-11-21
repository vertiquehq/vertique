// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared, array-aware redactor for log-safe rendering of config that may carry secrets.
 *
 * <p>Open property bags ({@link JsonObject}/{@link JsonArray} pass-through subtrees)
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
 *       secret} and rendering each string at a {@link #isUriPath(String) URL-named} path through
 *       {@code redactUri}.</li>
 *   <li>{@link #redactUri(String)} — masks the authority userinfo (the {@code user:pass} between
 *       {@code //} and {@code @}), an Oracle thin {@code user/pass@} prefix, and the value of any
 *       credential-bearing parameter, whether {@code &}-, {@code ;}- or {@code ?}-separated.</li>
 * </ul>
 *
 * <p>{@link #isUriPath(String)} is the companion predicate for the second shape: it names the dotted
 * paths ({@code jdbcUrl}, {@code baseUrl}, {@code endpoints}, {@code uri}, {@code connectionString})
 * whose string value is a URL the config mapper renders through {@code redactUri} when it serializes a
 * configuration record. A path that names a secret always wins: callers test {@code isSensitivePath}
 * first and mask the whole value, so a {@code tokenUrl} renders as {@link #MASK}, not as a URL.
 *
 * <p>The secret-path predicate is the <em>union</em> of every token any module needs, so a bag
 * rendered through this redactor is masked consistently regardless of which module owns it. Besides
 * the dotted-substring tokens ({@code password}, {@code secret}, {@code token}, {@code passphrase},
 * {@code credential}, {@code jaas.config}, {@code user.info}, {@code private.key}, {@code keystore},
 * {@code truststore}), it also matches separator-normalized credential tokens ({@code apikey},
 * {@code accesskey}, {@code secretkey}, {@code privatekey}, {@code jaasconfig}, {@code userinfo}) so
 * {@code apiKey}/{@code api-key}/{@code api.key}, {@code accessKey}/{@code access-key}/{@code access.key},
 * {@code privateKeyPem}/{@code private-key}/{@code private_key}, {@code jaasConfig}/{@code jaas-config}
 * and {@code userInfo}/{@code user_info} are all masked regardless of casing or separator.
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
     * {@code access.key}, {@code secretKey}, {@code privateKeyPem}, {@code private-key}, {@code jaasConfig},
     * {@code userInfo}, etc. match a single token regardless of how the operator spelled them. The dotted
     * {@code jaas.config} and {@code user.info} tokens stay in the dotted list; the normalized forms add
     * the camel-case and hyphenated spellings.
     */
    private static final List<String> NORMALIZED_SECRET_TOKENS =
            List.of("apikey", "accesskey", "secretkey", "privatekey", "jaasconfig", "userinfo");

    /**
     * Separator-normalized URL tokens. A path names a URL-valued string when its normalized form —
     * lower-cased with {@code .}, {@code -}, and {@code _} stripped — contains any of these, so
     * {@code jdbcUrl}, {@code base-url}, {@code redis.endpoints}, {@code uri} and
     * {@code connection_string} all match. The set may widen in a minor release (widening only adds
     * masking) and never narrows.
     */
    private static final List<String> URI_TOKENS = List.of("url", "uri", "endpoint", "connectionstring");

    /** Parameter names that mark a credential only as the whole name, in a URI. */
    private static final Set<String> EXACT_CREDENTIAL_PARAMETERS = Set.of("pwd", "passwd", "sig");

    private static final String ORACLE_THIN_PREFIX = "jdbc:oracle:thin:";

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
     *       {@code accesskey}, {@code secretkey}, {@code privatekey}, {@code jaasconfig},
     *       {@code userinfo}), so that {@code apiKey}, {@code api-key}, {@code api.key}, {@code accessKey},
     *       {@code access-key}, {@code access.key}, {@code privateKeyPem}, {@code private-key},
     *       {@code private_key}, {@code jaasConfig}, {@code jaas-config}, {@code userInfo} and
     *       {@code user_info} all match regardless of separator/casing convention.</li>
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
     * Returns {@code true} when the dotted config path names a URL-valued string: its
     * <em>separator-normalized</em> form — lower-cased with {@code .}, {@code -}, and {@code _} stripped
     * — contains {@code url}, {@code uri}, {@code endpoint} or {@code connectionstring}. The match is a
     * substring match, so {@code jdbcUrl}, {@code restClient.orders.baseUrl}, {@code redis.endpoints}
     * and {@code sql.connection-string} are recognized at any depth and in any casing or separator
     * convention.
     *
     * <p>The predicate is deliberately biased towards matching: a missed name leaks a credential, while
     * a wrong match costs at most a rewritten non-URL string that happens to contain {@code //…@} or a
     * credential-named {@code ?name=value}, because {@link #redactUri(String)} returns a string equal to
     * its input otherwise. Look-alikes such as {@code security} and {@code during} (both contain
     * {@code uri}) therefore match by design.
     * Names that do not contain one of the tokens — {@code address}, {@code host},
     * {@code bootstrap.servers} — are not recognized even when they hold a URL.
     *
     * <p>A secret path takes precedence: callers test {@link #isSensitivePath(String)} first, so a path
     * that is both ({@code tokenUrl}) is masked whole as {@link #MASK}.
     *
     * @param dottedPath the full dotted path of a config leaf (must not be {@code null})
     * @return {@code true} when the path names a URL-valued string
     */
    public static boolean isUriPath(String dottedPath) {
        String normalized = dottedPath
                .toLowerCase(Locale.ROOT)
                .replace(".", "")
                .replace("-", "")
                .replace("_", "");
        return URI_TOKENS.stream().anyMatch(normalized::contains);
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
     * <p>A string leaf whose path {@link #isUriPath(String) names a URL} is rendered through
     * {@link #redactUri(String)}, the same rule the config mapper applies when it serializes a record, so a
     * credentialed URL in a bag is masked in {@code toString()} and in a mapper dump alike.
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
            } else if (value instanceof CharSequence text && isUriPath(fullKey)) {
                masked.put(key, redactUri(text.toString()));
            } else {
                masked.put(key, value);
            }
        }
        return masked;
    }

    /**
     * Recursively builds a masked copy of {@code array}. Each element keeps the parent's dotted path
     * (the array index is not part of the key shape), so object/array elements are descended into,
     * string elements at a {@link #isUriPath(String) URL-named} path are rendered through
     * {@link #redactUri(String)}, and other scalar elements are copied verbatim (a sensitive array key
     * is already handled by the caller, which masks the whole value before recursing here).
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
            } else if (value instanceof CharSequence text && isUriPath(prefix)) {
                masked.add(redactUri(text.toString()));
            } else {
                masked.add(value);
            }
        }
        return masked;
    }

    /**
     * Returns a log-safe rendering of a URI with credentials masked: the authority <em>userinfo</em>
     * (the {@code user:pass} between {@code //} and {@code @}), the {@code user/pass} of an Oracle thin
     * JDBC URL ({@code jdbc:oracle:thin:user/pass@host…}), and the value of every parameter whose name
     * {@link #isSensitivePath(String) names a credential} or is {@code pwd}, {@code passwd} or
     * {@code sig}. Parameters are separated by {@code &} or {@code ;} and start at the first
     * {@code ?} or {@code ;}, so the query of a web URL and the property list of a JDBC URL
     * ({@code …:1433;password=…}) are masked by one rule. A value written {@code RAW(…)} or
     * {@code RAW{…}} (Camel) is masked whole, even when it contains a separator. The scheme, host, port,
     * and path are kept intact for debuggability.
     *
     * <p>Known limit: a credential value that contains an unescaped {@code ;} or {@code &} ends at
     * that character, so the rest renders as given; write such a value as {@code RAW(…)}. A parameter
     * name that is not one of the credential names above, such as {@code saslJaasConfig} in a camel-cased
     * spelling the path predicate does not know, is not masked.
     *
     * <p>This is a best-effort string redaction that never throws: a {@code null} URI renders as
     * {@code "null"}; a URI with no scheme, no userinfo, or no parameters is handled gracefully. It does
     * not require a well-formed {@link java.net.URI} (Camel endpoint URIs frequently are not), so it
     * operates on the raw string rather than parsing.
     *
     * @param uri the URI to redact, or {@code null}
     * @return the URI with userinfo and credential parameter values masked, or {@code "null"} when
     *     {@code null}
     */
    public static String redactUri(String uri) {
        if (uri == null) {
            return "null";
        }
        return redactParameters(redactUserinfo(redactOracleThin(uri)));
    }

    /**
     * Masks the {@code user/pass} (or bare {@code user}) of an Oracle thin JDBC URL, the text between
     * {@code jdbc:oracle:thin:} and the {@code @} that follows it. A URL with nothing there
     * ({@code jdbc:oracle:thin:@//host/service}) is returned unchanged.
     *
     * @param uri the URI string (never {@code null})
     * @return the URI with any Oracle thin credentials replaced by {@link #MASK}
     */
    private static String redactOracleThin(String uri) {
        if (!uri.regionMatches(true, 0, ORACLE_THIN_PREFIX, 0, ORACLE_THIN_PREFIX.length())) {
            return uri;
        }
        int from = ORACLE_THIN_PREFIX.length();
        int at = uri.indexOf('@', from);
        if (at <= from) {
            return uri;
        }
        return uri.substring(0, from) + MASK + uri.substring(at);
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
     * Masks the value of every credential-bearing parameter. Parameters start at the first {@code ?} or
     * {@code ;} and are separated by {@code &} or {@code ;}; each {@code name=value} pair whose name is
     * a {@link #isCredentialParameter(String) credential name} has its value replaced with {@link #MASK}.
     * A URI with no parameters is returned unchanged, and every other character is copied as given.
     *
     * @param uri the URI string (never {@code null}; may already have userinfo masked)
     * @return the URI with credential parameter values masked
     */
    private static String redactParameters(String uri) {
        int start = firstDelimiter(uri);
        if (start < 0) {
            return uri;
        }
        StringBuilder redacted = new StringBuilder(uri.length()).append(uri, 0, start);
        int delimiter = start;
        while (delimiter < uri.length()) {
            redacted.append(uri.charAt(delimiter));
            int nameStart = delimiter + 1;
            int equals = -1;
            int end = nameStart;
            while (end < uri.length() && !isParameterSeparator(uri.charAt(end))) {
                if (uri.charAt(end) == '=' && equals < 0) {
                    equals = end;
                    end = rawValueEnd(uri, end + 1);
                    continue;
                }
                end++;
            }
            if (equals >= 0 && isCredentialParameter(uri.substring(nameStart, equals))) {
                redacted.append(uri, nameStart, equals + 1).append(MASK);
            } else {
                redacted.append(uri, nameStart, end);
            }
            delimiter = end;
        }
        return redacted.toString();
    }

    private static int firstDelimiter(String uri) {
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '?' || c == ';') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isParameterSeparator(char c) {
        return c == '&' || c == ';';
    }

    /**
     * Returns where a parameter value that starts at {@code from} ends when it is written as a Camel
     * raw value, {@code RAW(…)} or {@code RAW{…}}: the closing bracket that is followed by {@code &} or
     * the end of the string, or the end of the string when there is none. Any other value ends where
     * the caller finds a separator, so this returns {@code from} unchanged.
     */
    private static int rawValueEnd(String uri, int from) {
        char close;
        if (uri.startsWith("RAW(", from)) {
            close = ')';
        } else if (uri.startsWith("RAW{", from)) {
            close = '}';
        } else {
            return from;
        }
        for (int i = from + 4; i < uri.length(); i++) {
            if (uri.charAt(i) == close && (i + 1 == uri.length() || uri.charAt(i + 1) == '&')) {
                return i + 1;
            }
        }
        return uri.length();
    }

    /**
     * Returns {@code true} when a URI parameter name carries a credential: it
     * {@link #isSensitivePath(String) names a secret} or equals, ignoring case, {@code pwd},
     * {@code passwd} or {@code sig}. The three short names match whole only, because as substrings they
     * would match {@code design} or {@code signal}.
     */
    private static boolean isCredentialParameter(String name) {
        return isSensitivePath(name) || EXACT_CREDENTIAL_PARAMETERS.contains(name.toLowerCase(Locale.ROOT));
    }
}
