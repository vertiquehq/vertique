// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.scope;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScopeKey} construction, character contract, exact identity, and DEFAULT.
 */
class ScopeKeyTest {

    private static final String ECHO_SENTINEL = "deny-U+0000-secret";

    @Test
    @DisplayName("accepts exactly the ScopeKey character contract")
    void acceptsExactlyTheScopeKeyCharacterContract() {
        List<NamedCheck> checks = new ArrayList<>();

        for (String valid : List.of(
                "acme",
                "a b",
                "a\u00A0b",
                "a\u3000b",
                "a\u2028b",
                "a\u2029b",
                repeatCodePoint(0x1F600, 255),
                "\uE000",
                "\uFFFF")) {
            checks.add(named("accept:" + describe(valid), () -> {
                ScopeKey key = new ScopeKey(valid);
                assertEquals(valid, key.value());
            }));
        }

        checks.add(named("reject:null", () -> assertThrows(NullPointerException.class, () -> new ScopeKey(null))));

        for (String invalid : invalidKeys()) {
            String label = "reject:" + describe(invalid);
            checks.add(named(label, () -> {
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new ScopeKey(invalid));
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                assertTrue(!message.contains(ECHO_SENTINEL), () -> "message must not echo sentinel: " + message);
                // Distinctive multi-character inputs must not appear in the diagnostic.
                if (invalid.length() > 1 && !invalid.isBlank()) {
                    assertTrue(!message.contains(invalid), () -> "message must not echo input: " + message);
                }
            }));
        }

        assertAll(checks.toArray(org.junit.jupiter.api.function.Executable[]::new));
    }

    @Test
    @DisplayName("preserves exact value identity and DEFAULT")
    void preservesExactValueIdentityAndDefault() {
        String left = new String("Acme");
        String right = new String("Acme");
        assertNotEquals(System.identityHashCode(left), System.identityHashCode(right));

        ScopeKey a = new ScopeKey(left);
        ScopeKey b = new ScopeKey(right);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("Acme", a.value());
        assertEquals("Acme", b.value());

        assertNotEquals(new ScopeKey("Acme"), new ScopeKey("acme"));
        assertNotEquals(new ScopeKey("\u00E9"), new ScopeKey("e\u0301"));

        assertEquals(new ScopeKey("default"), ScopeKey.DEFAULT);
        assertEquals("default", ScopeKey.DEFAULT.value());
        assertEquals(new ScopeKey("system"), new ScopeKey("system"));
        assertNotEquals(ScopeKey.DEFAULT, new ScopeKey("system"));
    }

    private static List<String> invalidKeys() {
        List<String> invalid = new ArrayList<>();
        invalid.add("");
        invalid.add(" ");
        invalid.add("\t");
        invalid.add("\u00A0");
        invalid.add("\u3000");
        invalid.add("\u2028");
        invalid.add("\u2029");
        invalid.add("a".repeat(256));
        invalid.add(repeatCodePoint(0x1F600, 256));
        invalid.add(String.valueOf(Character.MIN_HIGH_SURROGATE));
        invalid.add(String.valueOf(Character.MIN_LOW_SURROGATE));
        invalid.add("\u200B");
        invalid.add("a\u200Bb");
        invalid.add("\u200D");
        invalid.add("\u202E");
        invalid.add("\uFEFF");
        invalid.add(" acme");
        invalid.add("acme ");
        invalid.add("\u00A0acme");
        invalid.add("acme\u00A0");
        invalid.add("\u3000acme");
        invalid.add("acme\u3000");
        invalid.add("\u2028acme");
        invalid.add("acme\u2028");
        invalid.add("\u2029acme");
        invalid.add("acme\u2029");
        invalid.add("\u0000" + ECHO_SENTINEL);
        IntStream.rangeClosed(0x00, 0x1F).forEach(cp -> invalid.add(codePointString(cp)));
        IntStream.rangeClosed(0x7F, 0x9F).forEach(cp -> invalid.add(codePointString(cp)));
        return invalid;
    }

    private static String repeatCodePoint(int codePoint, int count) {
        String unit = codePointString(codePoint);
        return unit.repeat(count);
    }

    private static String codePointString(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static String describe(String value) {
        if (value == null) {
            return "null";
        }
        if (value.isEmpty()) {
            return "empty";
        }
        if (value.length() > 40) {
            return "len=" + value.length() + "/cp=" + value.codePointCount(0, value.length());
        }
        StringBuilder sb = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(String.format("U+%04X", cp));
        });
        return sb.toString();
    }

    private static NamedCheck named(String name, org.junit.jupiter.api.function.Executable executable) {
        return new NamedCheck(name, executable);
    }

    private record NamedCheck(String name, org.junit.jupiter.api.function.Executable executable)
            implements org.junit.jupiter.api.function.Executable {
        @Override
        public void execute() throws Throwable {
            try {
                executable.execute();
            } catch (AssertionError error) {
                throw new AssertionError(name + ": " + error.getMessage(), error);
            }
        }
    }
}
