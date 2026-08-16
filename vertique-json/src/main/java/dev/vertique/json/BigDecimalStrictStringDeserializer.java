// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Opt-in, <em>strict</em> Jackson deserializer for {@link BigDecimal} that accepts only a JSON
 * <strong>string</strong> token as input, and only within a deliberately narrow decimal grammar.
 *
 * <p>This is the strict counterpart to {@link BigDecimalAsStringSerializer}. Accepted input: a JSON
 * string whose content is a <strong>plain decimal literal</strong> matching
 * {@code -?[0-9]+(\.[0-9]+)?} and no longer than {@value #MAX_LENGTH} characters, with
 * <strong>no surrounding whitespace</strong> (e.g. {@code "1.50"} → {@code new BigDecimal("1.50")}).
 * The string is never trimmed, normalized, or coerced; anything outside the grammar is rejected
 * with a clean {@link com.fasterxml.jackson.databind.exc.MismatchedInputException}, including:
 * <ul>
 *   <li>JSON numbers ({@code 1.5}) — rejected, even though they are valid decimals.</li>
 *   <li>{@code true} / {@code false} — rejected.</li>
 *   <li>JSON objects ({@code {}}) — rejected.</li>
 *   <li>JSON arrays ({@code []}) — rejected.</li>
 *   <li>Whitespace-padded strings ({@code " 1.5 "}) — rejected; the string must be an exact
 *       decimal literal with no surrounding whitespace.</li>
 *   <li>Exponent notation ({@code "1e5"}, {@code "1E+2"}, {@code "1e-2000000000"}) — rejected;
 *       see the amplification note below.</li>
 *   <li>Literals outside the plain grammar ({@code "+1.5"}, {@code ".5"}, {@code "1."},
 *       {@code "0x1F"}) — rejected.</li>
 *   <li>Literals longer than {@value #MAX_LENGTH} characters — rejected.</li>
 *   <li>Malformed numeric strings ({@code "abc"}) — rejected with a clean mapping error,
 *       never a raw {@link NumberFormatException} or {@code NullPointerException}.</li>
 * </ul>
 *
 * <p><strong>Why exponent notation is rejected.</strong> {@code new BigDecimal(String)} accepts
 * scientific notation, where a handful of wire characters can select an enormous scale:
 * {@code "1e-2000000000"} is 13 characters but yields a value of scale 2·10<sup>9</sup>. Because
 * {@link BigDecimalAsStringSerializer} re-serializes via {@link BigDecimal#toPlainString()}, echoing
 * such a value back would materialize gigabytes of digits — a wire-facing amplification denial of
 * service. Jackson's {@code StreamReadConstraints} number limits do <em>not</em> apply to string
 * tokens, so the bound is enforced here. Restricting the grammar to plain decimals of at most
 * {@value #MAX_LENGTH} characters caps both the precision and the scale of any parsed value.
 *
 * <p>The bound costs no round-trip fidelity for the matched pair: {@link BigDecimalAsStringSerializer}
 * emits {@link BigDecimal#toPlainString()}, which never produces exponent notation, so every value
 * this framework writes re-parses through this deserializer (subject to the length bound).
 *
 * <p>A JSON {@code null} is <strong>allowed</strong> and yields {@code null} (the standard
 * "absent value" semantics): Jackson routes a {@code null} token through the null-value provider, so
 * {@link #deserialize(JsonParser, DeserializationContext) deserialize} is never invoked for it. Only
 * non-{@code null}, non-string tokens are rejected.
 *
 * <p>This deserializer is part of a <strong>matched opt-in pair</strong> with
 * {@link BigDecimalAsStringSerializer}: register both on an application-owned
 * {@link com.fasterxml.jackson.databind.ObjectMapper} via a
 * {@link com.fasterxml.jackson.databind.module.SimpleModule} when a strict string wire form of
 * {@code BigDecimal} is required. This deserializer is <strong>not</strong> registered by
 * {@link JacksonDefaults} or the {@code vertique} profile.
 *
 * @see BigDecimalAsStringSerializer
 */
public final class BigDecimalStrictStringDeserializer extends JsonDeserializer<BigDecimal> {

    // --- Grammar bounds ---

    /**
     * Maximum accepted length, in characters, of the decimal literal carried by the JSON string.
     *
     * <p>Package-private (not {@code private}) so {@link BigDecimalAsStringSerializer} enforces the
     * identical write-side bound off the same constant — the pair cannot drift apart (json-004).
     */
    static final int MAX_LENGTH = 100;

    /**
     * The only accepted literal shape: an optionally negative integer part with an optional
     * fraction part. Exponent notation, a leading {@code +}, a bare {@code .5}, and a trailing
     * {@code 1.} are all outside this grammar.
     */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    /**
     * The JSON Schema {@code pattern} form of {@link #PLAIN_DECIMAL}'s grammar, anchored with a
     * leading {@code ^} and a trailing {@code $}.
     *
     * <p>Anchoring is required because JSON Schema {@code pattern} evaluation is an
     * <strong>unanchored search</strong> (ECMA-262 {@code RegExp.test} semantics), unlike
     * {@link java.util.regex.Matcher#matches()}, which this deserializer itself uses. Without the
     * anchors, a schema validator would accept a string merely <em>containing</em> a plain decimal
     * substring rather than requiring the whole string to be one.
     *
     * <p>Package-private (not {@code private}) so the {@code vertique-strict} profile's declared
     * {@link dev.vertique.core.json.JsonSchemaTypeOverride} fragment (FR-JSON-089) is built directly
     * from this derivation — never from a second, independently maintained copy of the grammar — so
     * the published schema and the serde's accepted grammar cannot drift apart.
     */
    static final String ANCHORED_PLAIN_DECIMAL_PATTERN = "^" + PLAIN_DECIMAL.pattern() + "$";

    // --- Deserialization ---

    /**
     * Deserializes a {@link BigDecimal} from a JSON string token only.
     *
     * <p>Accepts {@code VALUE_STRING} whose content is a plain decimal literal matching
     * {@code -?[0-9]+(\.[0-9]+)?} and no longer than {@value #MAX_LENGTH} characters, with no
     * surrounding whitespace. Rejects every other token — including JSON numbers, booleans,
     * objects, and arrays — as well as whitespace-padded, over-length, exponent-bearing, and
     * otherwise malformed decimal strings, with a clean
     * {@link com.fasterxml.jackson.databind.exc.MismatchedInputException} in all cases.
     *
     * <p><strong>Message hygiene.</strong> The rejection message never echoes the submitted string —
     * only its length and the expected grammar are named — so a malicious or malformed request body
     * cannot smuggle attacker-controlled text into a log line via this exception's message
     * (log-injection hygiene, json-004).
     *
     * @param p    the {@link JsonParser} positioned at the value token
     * @param ctxt the {@link DeserializationContext} for error reporting
     * @return the parsed {@link BigDecimal}
     * @throws IOException                                                     if the underlying parser throws
     * @throws com.fasterxml.jackson.databind.exc.MismatchedInputException    if the token is not a JSON string,
     *                                                                          or the string is not an accepted
     *                                                                          plain decimal literal
     */
    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            throw MismatchedInputException.from(
                    p,
                    BigDecimal.class,
                    "Expected a JSON string containing a decimal literal; got " + p.currentToken());
        }
        try {
            return parseBounded(p.getText());
        } catch (IllegalArgumentException rejected) {
            throw MismatchedInputException.from(p, BigDecimal.class, rejected.getMessage());
        }
    }

    // --- Shared bounded parsing ---

    /**
     * Parses {@code text} as a {@link BigDecimal} under this deserializer's bounded plain-decimal
     * grammar, independent of any Jackson parser/context.
     *
     * <p>Shared by {@link #deserialize(JsonParser, DeserializationContext)} (JSON string values) and
     * the {@code vertique-strict} profile's {@code BigDecimal} map-key deserializer, so the length and
     * grammar bound is enforced identically for both a decimal <em>value</em> and a decimal
     * <em>map key</em> — a JSON object key cannot smuggle an exponent-notation literal past the
     * value-side bound (json-004).
     *
     * <p><strong>Message hygiene.</strong> The thrown message never echoes {@code text} — only its
     * length and the expected grammar are named (log-injection hygiene, json-004).
     *
     * @param text the candidate decimal literal, exactly as read from a JSON string value or a JSON
     *             object's map key
     * @return the parsed {@link BigDecimal}
     * @throws IllegalArgumentException if {@code text} is longer than {@value #MAX_LENGTH} characters,
     *         does not match the plain decimal grammar {@code -?[0-9]+(\.[0-9]+)?}, or is not
     *         parseable as a {@link BigDecimal}
     */
    static BigDecimal parseBounded(String text) {
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Decimal string exceeds the maximum length of " + MAX_LENGTH
                    + " characters: " + text.length() + " characters");
        }
        if (!PLAIN_DECIMAL.matcher(text).matches()) {
            throw new IllegalArgumentException(
                    "Decimal string does not match the accepted plain decimal grammar -?[0-9]+(\\.[0-9]+)? "
                            + "(exponent notation is deliberately rejected); rejected value length: " + text.length()
                            + " characters");
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Not a valid decimal string; rejected value length: " + text.length() + " characters", e);
        }
    }
}
