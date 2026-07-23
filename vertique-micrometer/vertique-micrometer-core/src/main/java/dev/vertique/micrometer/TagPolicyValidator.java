// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dev.vertique.core.exception.ConfigurationException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates {@link MetricsConfig.TagsConfig} at bootstrap time, failing fast when any
 * tag policy rule is violated.
 *
 * <p>Rules enforced (plan D-M rev 5):
 * <ol>
 *   <li><b>Extra map size</b> — at most 16 entries.</li>
 *   <li><b>Key format</b> — every extra key must match {@code ^[a-z][a-z0-9._-]{0,63}$}.</li>
 *   <li><b>Reserved keys</b> — the key {@code "service"}, any key starting with
 *       {@code "vertique."}, and any key that is a member of
 *       {@link CardinalityGuard#GUARDED_TAG_KEYS} are rejected.</li>
 *   <li><b>Secret-like key rejection by segment</b> — the key is split on {@code [._-]};
 *       any whole segment that equals a credential indicator word is rejected; the adjacent
 *       segment pairs {@code ("api", "key")}, {@code ("access", "key")}, and
 *       {@code ("private", "key")} are also rejected wherever they appear.
 *       Credential indicator words: {@code password}, {@code passwd}, {@code secret},
 *       {@code token}, {@code credential}, {@code apikey}, {@code authorization},
 *       {@code bearer}, {@code accesskey}, {@code privatekey}.</li>
 *   <li><b>Value rules</b> — every non-null value (extra values and the {@code service} value
 *       when non-null) must be a {@link String} of at most 256 characters and must not match a
 *       credential-shape prefix ({@code eyJ}, {@code AKIA}, {@code ghp_}, {@code xox[bp]-},
 *       {@code Bearer } case-insensitive, or {@code Basic } case-insensitive).</li>
 * </ol>
 *
 * <p>Exception messages name the offending <em>key</em> and the violated rule. For value
 * violations the message names the key and the rule, but <b>never</b> any part of the value.
 *
 * @see MetricsConfig.TagsConfig
 * @see ConfigurationException
 */
final class TagPolicyValidator {

    // --- Constants ---

    /** Maximum number of entries allowed in the {@code extra} map. */
    private static final int MAX_EXTRA_ENTRIES = 16;

    /** Maximum number of characters allowed in a tag value. */
    private static final int MAX_VALUE_LENGTH = 256;

    /**
     * Compiled pattern for valid extra key names: starts with a lowercase letter, followed by
     * up to 63 more characters each of which may be a lowercase letter, digit, dot, underscore,
     * or hyphen. Total maximum length is 64.
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");

    /**
     * Separator characters used to split a key into segments for credential indicator matching.
     * Splitting on {@code [._-]} means each segment is a single word.
     */
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("[._-]");

    /**
     * Whole-segment words whose presence in a key's segment list indicates the key may hold
     * a secret. Matching is exact (the segment must equal one of these words, not merely contain
     * it) so that {@code "tokenizer"} or {@code "secretariat"} are accepted.
     *
     * <p>Extended set includes HTTP-auth related indicators ({@code authorization}, {@code bearer})
     * and credential-identifier forms ({@code accesskey}, {@code privatekey}).
     */
    private static final Set<String> CREDENTIAL_SEGMENTS = Set.of(
            "password",
            "passwd",
            "secret",
            "token",
            "credential",
            "apikey",
            "authorization",
            "bearer",
            "accesskey",
            "privatekey");

    /**
     * The reserved key name {@code "service"} — managed by the framework and not user-settable
     * via the extra map.
     */
    private static final String RESERVED_KEY_SERVICE = "service";

    /**
     * Reserved key prefix for all framework-owned keys.
     */
    private static final String RESERVED_KEY_PREFIX_VERTIQUE = "vertique.";

    /**
     * Compiled pattern for credential-shape value prefixes that suggest the value holds a
     * credential rather than a metadata label. Matching is case-insensitive for HTTP auth header
     * prefixes ({@code Bearer}, {@code Basic}) to catch mixed-case variants; all other prefixes
     * are case-sensitive because their credential shapes have fixed casing.
     *
     * <ul>
     *   <li>{@code eyJ} — base64url-encoded JSON header (JWT).</li>
     *   <li>{@code AKIA} — AWS IAM access key ID.</li>
     *   <li>{@code ghp_} — GitHub personal access token.</li>
     *   <li>{@code xoxb-} / {@code xoxp-} — Slack bot / user OAuth token.</li>
     *   <li>{@code Bearer } — HTTP {@code Authorization: Bearer <token>} header value.</li>
     *   <li>{@code Basic } — HTTP {@code Authorization: Basic <credentials>} header value.</li>
     * </ul>
     */
    private static final Pattern CREDENTIAL_VALUE_PATTERN =
            Pattern.compile("^(eyJ|AKIA|ghp_|xox[bp]-|(?i)bearer |(?i)basic ).*");

    /** Prevent instantiation — this class is a static utility. */
    private TagPolicyValidator() {}

    // --- Public API ---

    /**
     * Validates the given {@link MetricsConfig.TagsConfig}, throwing a
     * {@link ConfigurationException} on the first rule violation encountered.
     *
     * <p>Validation order:
     * <ol>
     *   <li>Extra map size (rule 1).</li>
     *   <li>For each extra entry: key format (rule 2), reserved keys (rule 3), secret-like key
     *       segments (rule 4), then value checks (rule 5).</li>
     *   <li>Service value check (rule 5, applied after extra entries).</li>
     * </ol>
     *
     * @param tags the tag configuration to validate; must not be {@code null}
     * @throws ConfigurationException if any rule is violated; the message names the offending key
     *                                and rule but never any secret value
     */
    static void validate(MetricsConfig.TagsConfig tags) {
        Map<String, String> extra = tags.extra();

        // --- Rule 1: extra map size ---
        if (extra != null && extra.size() > MAX_EXTRA_ENTRIES) {
            throw new ConfigurationException("metrics.tags.extra exceeds the maximum of " + MAX_EXTRA_ENTRIES
                    + " entries; " + "actual count: " + extra.size());
        }

        // --- Rules 2-5: per-entry key and value validation ---
        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                String key = entry.getKey();
                validateKey(key);
                validateValue(key, entry.getValue());
            }
        }

        // --- Rule 5 (service value) ---
        String service = tags.service();
        if (service != null) {
            validateValue("service", service);
        }
    }

    // --- Private helpers ---

    /**
     * Validates a single extra key against rules 2, 3, and 4.
     *
     * @param key the extra tag key to validate
     * @throws ConfigurationException if the key violates any rule; message names the key and rule
     */
    private static void validateKey(String key) {
        // Rule 2: key format
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new ConfigurationException("metrics.tags.extra key '" + key + "' is invalid; "
                    + "keys must match ^[a-z][a-z0-9._-]{0,63}$ "
                    + "(lowercase start, max 64 chars, only letters/digits/dots/underscores/hyphens)");
        }

        // Rule 3a: reserved key 'service'
        if (RESERVED_KEY_SERVICE.equals(key)) {
            throw new ConfigurationException("metrics.tags.extra key '" + key + "' is reserved; "
                    + "use metrics.tags.service to set the service tag");
        }

        // Rule 3b: reserved prefix 'vertique.'
        if (key.startsWith(RESERVED_KEY_PREFIX_VERTIQUE)) {
            throw new ConfigurationException("metrics.tags.extra key '" + key + "' is reserved; "
                    + "keys starting with 'vertique.' are reserved for framework use");
        }

        // Rule 3c: collision with CardinalityGuard.GUARDED_TAG_KEYS
        if (CardinalityGuard.GUARDED_TAG_KEYS.contains(key)) {
            throw new ConfigurationException("metrics.tags.extra key '" + key + "' is reserved; "
                    + "it is a framework-managed cardinality-guarded tag key");
        }

        // Rule 4: secret-like key rejection by segment
        validateKeySegments(key);
    }

    /**
     * Validates that no segment of the given key equals a credential indicator word, and that
     * none of the adjacent segment pairs {@code ("api", "key")}, {@code ("access", "key")}, or
     * {@code ("private", "key")} appear anywhere in the segment list.
     *
     * <p>Splitting is performed on {@code [._-]}. Matching is whole-segment and exact: a segment
     * {@code "tokenizer"} does not match {@code "token"}, and {@code "secretariat"} does not
     * match {@code "secret"}. The pair check catches {@code access_key}, {@code access-key},
     * {@code private_key}, {@code private-key} and their dotted variants.
     *
     * @param key the extra tag key whose segments are checked
     * @throws ConfigurationException if any segment matches a credential indicator
     */
    private static void validateKeySegments(String key) {
        String[] segments = SEGMENT_SEPARATOR.split(key);

        for (String segment : segments) {
            if (CREDENTIAL_SEGMENTS.contains(segment)) {
                throw new ConfigurationException("metrics.tags.extra key '" + key + "' is rejected; "
                        + "segment '" + segment + "' matches a credential indicator word — "
                        + "keys that name credential-holding fields are not allowed as tag keys");
            }
        }

        // Adjacent pair checks: these two-word compound forms name credential-holding fields
        for (int i = 0; i < segments.length - 1; i++) {
            String cur = segments[i];
            String next = segments[i + 1];
            if ("key".equals(next) && ("api".equals(cur) || "access".equals(cur) || "private".equals(cur))) {
                throw new ConfigurationException("metrics.tags.extra key '" + key + "' is rejected; "
                        + "adjacent segment pair ('" + cur + "','key') matches a credential indicator — "
                        + "keys that name credential-holding fields are not allowed as tag keys");
            }
        }
    }

    /**
     * Validates a tag value against rule 5: must be non-null, at most {@value #MAX_VALUE_LENGTH}
     * characters, and must not match a credential-shape prefix ({@code eyJ}, {@code AKIA},
     * {@code ghp_}, {@code xox[bp]-}, {@code Bearer } (case-insensitive), or
     * {@code Basic } (case-insensitive)).
     *
     * <p>If the value violates a rule the exception message names the offending {@code key} and
     * the violated rule, but <b>never</b> any part of the value.
     *
     * @param key   the tag key under which this value appears, used in the error message
     * @param value the tag value to validate
     * @throws ConfigurationException if the value violates any rule
     */
    private static void validateValue(String key, String value) {
        if (value == null) {
            throw new ConfigurationException(
                    "metrics.tags value for key '" + key + "' is null; tag values must be non-null strings");
        }

        if (value.length() > MAX_VALUE_LENGTH) {
            throw new ConfigurationException("metrics.tags value for key '" + key + "' exceeds the maximum length of "
                    + MAX_VALUE_LENGTH + " characters");
        }

        if (CREDENTIAL_VALUE_PATTERN.matcher(value).matches()) {
            throw new ConfigurationException(
                    "metrics.tags value for key '" + key + "' matches a credential-shape pattern "
                            + "(e.g. JWT, AWS key, GitHub token, Slack token); "
                            + "tag values must not hold credentials");
        }
    }
}
