// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link ViolationPathForm#accepts(String)}: a JSON pointer form admits only the empty
 * pointer or a {@code /}-prefixed path that uses the escapes {@code ~0} and {@code ~1} only, holds no ISO
 * control character and neither line separator U+2028 nor paragraph separator U+2029, and is at most 2,000
 * characters long; a Java property path form admits every path.
 *
 * <p>Control and separator characters are built from their code points, never written into this source.
 */
class ViolationPathFormTest {

    private static final int MAX_LENGTH = 2000;

    /** A path of {@code length} characters that starts with a slash. */
    private static String pathOfLength(int length) {
        return "/" + "a".repeat(length - 1);
    }

    /** A pointer-shaped path with the given code point in the middle of a name. */
    private static String withCodePoint(int codePoint) {
        return "/a" + new String(Character.toChars(codePoint)) + "b";
    }

    private static Arguments row(String label, String path) {
        return Arguments.of(label, path);
    }

    static Stream<Arguments> wellFormedPointers() {
        return Stream.of(
                row("the empty pointer", ""),
                row("the member with the empty name", "/"),
                row("a nested path with an index", "/items/0/name"),
                row("an escaped tilde", "/a~0b"),
                row("an escaped slash", "/a~1b"),
                row("both escapes together", "/~0~1"),
                row("exactly 2,000 characters", pathOfLength(MAX_LENGTH)));
    }

    static Stream<Arguments> malformedPointers() {
        return Stream.of(
                row("no leading slash", "a/b"),
                row("a Java property path", "customer.items[0].name"),
                row("an escape without a leading slash", "~0"),
                row("a tilde at the end", "/a~"),
                row("a tilde with an unknown escape digit", "/a~2"),
                row("a tilde followed by a letter", "/a~x"),
                row("U+0000", withCodePoint(0x0000)),
                row("U+001F", withCodePoint(0x001F)),
                row("U+007F", withCodePoint(0x007F)),
                row("U+0080", withCodePoint(0x0080)),
                row("U+0085", withCodePoint(0x0085)),
                row("U+009F", withCodePoint(0x009F)),
                row("U+2028", withCodePoint(0x2028)),
                row("U+2029", withCodePoint(0x2029)),
                row("2,001 characters", pathOfLength(MAX_LENGTH + 1)),
                row("null", null));
    }

    static Stream<Arguments> everyRow() {
        return Stream.concat(wellFormedPointers(), malformedPointers());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wellFormedPointers")
    @DisplayName("the JSON pointer form accepts a well-formed pointer")
    void jsonPointerAcceptsOnlyWellFormedPointers(String label, String path) {
        // Given a well-formed pointer, When the JSON pointer form is asked, Then it accepts it
        assertTrue(ViolationPathForm.JSON_POINTER.accepts(path), label);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedPointers")
    @DisplayName("the JSON pointer form rejects everything else")
    void jsonPointerRejectsEverythingElse(String label, String path) {
        // Given a path that is not a well-formed pointer, When the JSON pointer form is asked, Then it rejects it
        assertFalse(ViolationPathForm.JSON_POINTER.accepts(path), label);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRow")
    @DisplayName("the Java property path form accepts every path, null included")
    void javaPropertyPathAcceptsEveryPath(String label, String path) {
        // Given any path, When the Java property path form is asked, Then it accepts it
        assertTrue(ViolationPathForm.JAVA_PROPERTY_PATH.accepts(path), label);
    }
}
