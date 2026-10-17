// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.vertx.json.schema.OutputUnit;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the seven reused format checks through the only door the module offers them, a validator compiled
 * by the pattern-input guard: each decides a string with its own rules instead of the engine's expression,
 * rejects without placing the value into the engine's message, and judges a very long string without
 * exhausting a small stack.
 */
class ReusedFormatChecksTest {

    @ParameterizedTest(name = "{0}: \"{1}\" is valid: {2}")
    @MethodSource("verdicts")
    @DisplayName("a guard-compiled validator gives each reused format the verdict of its own rules")
    void givesEachFormatTheVerdictOfItsOwnRules(String format, String value, boolean valid) {
        // Given a guard-compiled validator for a string with the format
        PatternInputGuard guard = new PatternInputGuard(PatternInputGuard.DEFAULT_MAX_CHARS, 262_144);

        // When the value is validated, Then the verdict is the format's
        OutputUnit result = GuardFixtures.validate(guard, Schemas.stringWithFormat(format), value);

        assertThat(result.getValid()).isEqualTo(valid);
    }

    static Stream<Arguments> verdicts() {
        return Stream.of(
                Arguments.of("uri", "https://example.com/a?b=c#d", true),
                Arguments.of("uri", "urn:isbn:0451450523", true),
                Arguments.of("uri", "http://[::1]/x", true),
                Arguments.of("uri", "not a uri", false),
                Arguments.of("uri", "//example.com/relative", false),
                Arguments.of("uri", "", false),
                Arguments.of("uri-reference", "../a/b", true),
                Arguments.of("uri-reference", "#frag", true),
                Arguments.of("uri-reference", "", true),
                Arguments.of("uri-reference", "//example.com/x", true),
                Arguments.of("uri-reference", "a b", false),
                Arguments.of("uri-reference", "http://[::1", false),
                Arguments.of("url", "https://example.com/path", true),
                Arguments.of("url", "http://example.com:8080/x?y#z", true),
                Arguments.of("url", "ftp://example.com/x", true),
                Arguments.of("url", "example.com", false),
                Arguments.of("url", "http://localhost", false),
                Arguments.of("url", "http://", false),
                Arguments.of("url", "http://exa mple.com", false),
                Arguments.of("uri-template", "http://example.com/{id}", true),
                Arguments.of("uri-template", "{+path}/here{?a,b}", true),
                Arguments.of("uri-template", "{var:3}", true),
                Arguments.of("uri-template", "{/list*}", true),
                Arguments.of("uri-template", "plain", true),
                Arguments.of("uri-template", "http://example.com/{id", false),
                Arguments.of("uri-template", "{}", false),
                Arguments.of("uri-template", "{ a}", false),
                Arguments.of("json-pointer", "", true),
                Arguments.of("json-pointer", "/foo/0", true),
                Arguments.of("json-pointer", "/a~1b", true),
                Arguments.of("json-pointer", "/a~0b", true),
                Arguments.of("json-pointer", "/", true),
                Arguments.of("json-pointer", "foo", false),
                Arguments.of("json-pointer", "/a~2b", false),
                Arguments.of("relative-json-pointer", "0", true),
                Arguments.of("relative-json-pointer", "1/foo", true),
                Arguments.of("relative-json-pointer", "2#", true),
                Arguments.of("relative-json-pointer", "/foo", false),
                Arguments.of("relative-json-pointer", "-1", false),
                Arguments.of("relative-json-pointer", "01", false),
                Arguments.of("relative-json-pointer", "", false),
                Arguments.of("relative-json-pointer", "a", false),
                Arguments.of("json-pointer-uri-fragment", "#", true),
                Arguments.of("json-pointer-uri-fragment", "#/foo/%20bar", true),
                Arguments.of("json-pointer-uri-fragment", "#/a~1b", true),
                Arguments.of("json-pointer-uri-fragment", "#/%C3%A9", true),
                Arguments.of("json-pointer-uri-fragment", "foo", false),
                Arguments.of("json-pointer-uri-fragment", "#/a%zz", false),
                Arguments.of("json-pointer-uri-fragment", "/foo", false));
    }

    @ParameterizedTest(name = "format {0}")
    @ValueSource(
            strings = {
                "uri",
                "uri-reference",
                "url",
                "uri-template",
                "json-pointer",
                "relative-json-pointer",
                "json-pointer-uri-fragment"
            })
    @DisplayName("a rejection never carries the submitted value")
    void aRejectionNeverCarriesTheSubmittedValue(String format) {
        PatternInputGuard guard = new PatternInputGuard(PatternInputGuard.DEFAULT_MAX_CHARS, 262_144);
        String value = "SECRET value {broken [not ~2 a valid thing";

        OutputUnit result = GuardFixtures.validate(guard, Schemas.stringWithFormat(format), value);

        assertThat(result.getValid()).isFalse();
        assertThat(result.getErrors().toString()).doesNotContain("SECRET");
    }

    @Test
    @DisplayName("a very long valid value is judged on a one-megabyte stack without overflow")
    void aVeryLongValueIsJudgedWithoutOverflow() {
        PatternInputGuard guard = new PatternInputGuard(PatternInputGuard.DEFAULT_MAX_CHARS, 262_144);
        String uri = "https://example.com/" + "a".repeat(100_000);
        String template = "http://example.com/" + "{a}".repeat(30_000);
        String pointer = "/" + "a/".repeat(50_000);

        for (Arguments row : new Arguments[] {
            Arguments.of("uri", uri),
            Arguments.of("uri-reference", uri),
            Arguments.of("url", uri),
            Arguments.of("uri-template", template),
            Arguments.of("json-pointer", pointer),
            Arguments.of("relative-json-pointer", "1" + pointer),
            Arguments.of("json-pointer-uri-fragment", "#" + pointer)
        }) {
            String format = (String) row.get()[0];
            String value = (String) row.get()[1];

            Object outcome = onSmallStack(() -> guard.validateFormat("string", "x-vertique-format-" + format, value));

            assertThat(outcome).as(format).isNull();
        }
    }

    /**
     * Runs {@code action} on a new thread with a one-megabyte stack inside a preemptive timeout, and returns
     * its result or the throwable it died with.
     */
    private static Object onSmallStack(Supplier<String> action) {
        AtomicReference<Object> outcome = new AtomicReference<>("not run");
        Thread thread = new Thread(
                null,
                () -> {
                    try {
                        outcome.set(action.get());
                    } catch (Throwable thrown) {
                        outcome.set(thrown);
                    }
                },
                "reused-format-small-stack",
                1 << 20);
        thread.setDaemon(true);
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            thread.start();
            thread.join();
        });
        return outcome.get();
    }
}
