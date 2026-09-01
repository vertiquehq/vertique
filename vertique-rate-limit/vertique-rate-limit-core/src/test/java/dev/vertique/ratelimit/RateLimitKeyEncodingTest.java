// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TP-001, TP-002, TP-005: {@link RateLimitKey}'s scalar allowlist, canonical encoding
 * distinctness, and 256-byte key-component byte bound
 * ({@code contracts/rate-limit-runtime.md}, "Key model", "Key-component byte bound").
 */
class RateLimitKeyEncodingTest {

    @Test
    void shouldRenderDistinctCanonicalKeysForDistinctComponentTuples() {
        RateLimitKey twoComponentsHyphenatedFirst = RateLimitKey.of("a-b", "c");
        RateLimitKey twoComponentsHyphenatedSecond = RateLimitKey.of("a", "b-c");
        RateLimitKey oneComponentWithColon = RateLimitKey.of("a:b");
        RateLimitKey twoPlainComponents = RateLimitKey.of("a", "b");
        RateLimitKey global = RateLimitKey.global();

        List<String> encodings = List.of(
                encodingOf(twoComponentsHyphenatedFirst),
                encodingOf(twoComponentsHyphenatedSecond),
                encodingOf(oneComponentWithColon),
                encodingOf(twoPlainComponents),
                encodingOf(global));

        assertThat(encodings).doesNotHaveDuplicates();
        assertThat(encodingOf(oneComponentWithColon)).isNotEqualTo(encodingOf(twoPlainComponents));
    }

    @Test
    void shouldAcceptExactAllowlistAndRejectNonFiniteOrUnsupportedScalars() {
        assertAccepted("caller-1");
        assertAccepted('x');
        assertAccepted(Boolean.TRUE);
        assertAccepted((byte) 1);
        assertAccepted((short) 2);
        assertAccepted(3);
        assertAccepted(4L);
        assertAccepted(BigInteger.TEN);
        assertAccepted(BigDecimal.valueOf(1.25));
        assertAccepted(1.5f);
        assertAccepted(2.5d);
        assertAccepted(Sample.ONLY_VALUE);
        assertAccepted(UUID.randomUUID());
        assertAccepted(Instant.parse("2026-01-01T00:00:00Z"));

        assertThatThrownBy(() -> RateLimitKey.of(Float.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimitKey.of(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimitKey.of(List.of("unsupported"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRenderDistinctCanonicalKeysAcrossPercentCraftedComponents() {
        // '%' must never be pass-through in the percent-encoded payload alphabet: if it were, a
        // literal '%' in one raw component could forge the encoding of an entirely different raw
        // component that happens to spell out that component's own percent-escapes (B1).
        RateLimitKey rawSlash = RateLimitKey.of("a/b");
        RateLimitKey rawPercentEscape = RateLimitKey.of("a%2Fb");
        RateLimitKey rawLiteralPercent = RateLimitKey.of("a%b");
        RateLimitKey rawDoubleEscapedPercent = RateLimitKey.of("a%25b");

        List<String> encodings = List.of(
                encodingOf(rawSlash),
                encodingOf(rawPercentEscape),
                encodingOf(rawLiteralPercent),
                encodingOf(rawDoubleEscapedPercent));

        assertThat(encodings)
                .as("distinct raw components, including crafted %%-sequences, must render distinct canonical keys")
                .doesNotHaveDuplicates();
        assertThat(encodingOf(RateLimitKey.of("a%b")))
                .as("same raw component re-encoded must be stable")
                .isEqualTo(encodingOf(rawLiteralPercent));
    }

    @Test
    void shouldReplaceOversizedComponentWithDistinctHashPreservingDistinctness() {
        String oversizedValueA = oversizedComponent('A');
        String oversizedValueB = oversizedComponent('B');
        String shortValue = "short-value";

        String encodedA = encodingOf(RateLimitKey.of(oversizedValueA));
        String encodedB = encodingOf(RateLimitKey.of(oversizedValueB));
        String encodedShort = encodingOf(RateLimitKey.of(shortValue));

        assertThat(encodedA).matches("h:[0-9a-f]{64}");
        assertThat(encodedB).matches("h:[0-9a-f]{64}");
        assertThat(encodedA).isNotEqualTo(encodedB);
        assertThat(encodedShort).isEqualTo("S" + shortValue);
    }

    @Test
    void shouldRenderDistinctHashesForOversizedComponentsOfDifferentTypesSharingTheSameRawPayload() {
        // Both String and BigInteger encode a purely-numeric payload identically via their own
        // toString(); without tag domain separation folded into the oversized-component hash, this
        // pair would collide on the same SHA-256 digest despite being distinct RateLimitKey
        // components (B1-style forgery, oversized-path variant).
        String digits = "9".repeat(300);
        BigInteger sameDigitsAsBigInteger = new BigInteger(digits);
        assertThat(sameDigitsAsBigInteger.toString()).isEqualTo(digits);

        String encodedString = encodingOf(RateLimitKey.of(digits));
        String encodedBigInteger = encodingOf(RateLimitKey.of(sameDigitsAsBigInteger));

        assertThat(encodedString).matches("h:[0-9a-f]{64}");
        assertThat(encodedBigInteger).matches("h:[0-9a-f]{64}");
        assertThat(encodedString)
                .as("String vs BigInteger, identical >256-byte payload, must not collide")
                .isNotEqualTo(encodedBigInteger);
    }

    /** Only the encoding-extraction accessor moves behind this small package-visible helper. */
    private static String encodingOf(RateLimitKey key) {
        return key.canonicalEncoding();
    }

    private static void assertAccepted(Object value) {
        assertThatCode(() -> {
                    RateLimitKey key = RateLimitKey.of(value);
                    assertThat(encodingOf(key)).isNotBlank();
                })
                .as(value.getClass().getName())
                .doesNotThrowAnyException();
    }

    /** An all-ASCII, all-percent-safe 300-character component: frames to 301 bytes, over the 256-byte bound. */
    private static String oversizedComponent(char last) {
        return "x".repeat(299) + last;
    }

    private enum Sample {
        ONLY_VALUE
    }
}
