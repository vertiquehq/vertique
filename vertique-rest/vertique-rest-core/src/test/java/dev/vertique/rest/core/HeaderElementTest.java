// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.request.HeaderElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HeaderElement}: quote-aware splitting of elements and parameters,
 * quoted-string unescaping, strict {@code q} handling, malformed-element skipping, the element
 * cap, and the guarantee that parsing never throws.
 */
class HeaderElementTest {

    private static HeaderElement only(String header) {
        List<HeaderElement> parsed = HeaderElement.parseList(header);
        assertEquals(1, parsed.size(), () -> "expected exactly one element for: " + header + " but got " + parsed);
        return parsed.get(0);
    }

    private static List<String> values(String header) {
        return HeaderElement.parseList(header).stream()
                .map(HeaderElement::value)
                .toList();
    }

    @Nested
    @DisplayName("plain headers")
    class Plain {

        @Test
        @DisplayName("parses value, parameters and default quality")
        void parsesPlainElement() {
            HeaderElement element = only("text/html; charset=utf-8");
            assertEquals("text/html", element.value());
            assertEquals(Map.of("charset", "utf-8"), element.parameters());
            assertEquals(1.0, element.quality(), 0.0);
        }

        @Test
        @DisplayName("splits a comma-separated list in header order")
        void splitsList() {
            assertEquals(List.of("a/b", "c/d", "e/f"), values("a/b, c/d ,e/f"));
        }

        @Test
        @DisplayName("parameter names are lowercased and keep header order; values keep their case")
        void lowercasesNamesAndKeepsOrder() {
            HeaderElement element = only("a/b; Zeta=Z; Alpha=A");
            assertEquals(
                    List.of("zeta", "alpha"),
                    new ArrayList<>(element.parameters().keySet()));
            assertEquals("Z", element.parameters().get("zeta"));
        }

        @Test
        @DisplayName("whitespace around separators and equals is trimmed")
        void trimsWhitespace() {
            HeaderElement element = only("  a/b  ;  x = 1  ;  q = 0.5  ");
            assertEquals("a/b", element.value());
            assertEquals(Map.of("x", "1"), element.parameters());
            assertEquals(0.5, element.quality(), 0.0);
        }

        @Test
        @DisplayName("works for non-media-type values such as Accept-Language and Accept-Encoding")
        void worksForOtherAcceptHeaders() {
            List<HeaderElement> languages = HeaderElement.parseList("fi;q=0.7, en-GB, *;q=0.1");
            assertEquals(
                    List.of("fi", "en-GB", "*"),
                    languages.stream().map(HeaderElement::value).toList());
            assertEquals(0.7, languages.get(0).quality(), 0.0);
            assertEquals(1.0, languages.get(1).quality(), 0.0);
            assertEquals(0.1, languages.get(2).quality(), 0.0);

            List<HeaderElement> encodings = HeaderElement.parseList("gzip;q=1.0, identity; q=0.5, *;q=0");
            assertEquals(
                    List.of("gzip", "identity", "*"),
                    encodings.stream().map(HeaderElement::value).toList());
            assertEquals(0.0, encodings.get(2).quality(), 0.0);
        }

        @Test
        @DisplayName("a parameter without = is ignored and the last duplicate parameter wins")
        void ignoresParameterWithoutEqualsAndLastDuplicateWins() {
            HeaderElement element = only("a/b; flag; x=1; x=2");
            assertEquals(Map.of("x", "2"), element.parameters());
        }

        @Test
        @DisplayName("a parameter with an empty name is ignored")
        void ignoresEmptyParameterName() {
            assertEquals(Map.of(), only("a/b; =v").parameters());
        }

        @Test
        @DisplayName("a backslash outside quotes is an ordinary character")
        void backslashOutsideQuotesIsLiteral() {
            assertEquals(Map.of("x", "a\\b"), only("a/b; x=a\\b").parameters());
        }

        @Test
        @DisplayName("parameters are immutable")
        void parametersAreImmutable() {
            Map<String, String> parameters = only("a/b; x=1").parameters();
            assertThrows(UnsupportedOperationException.class, () -> parameters.put("y", "2"));
        }

        @Test
        @DisplayName("the returned list is immutable")
        void listIsImmutable() {
            List<HeaderElement> parsed = HeaderElement.parseList("a/b");
            assertThrows(UnsupportedOperationException.class, () -> parsed.add(parsed.get(0)));
        }
    }

    @Nested
    @DisplayName("quoted strings")
    class Quoted {

        @Test
        @DisplayName("a quoted comma does not split elements and is unquoted")
        void quotedCommaDoesNotSplit() {
            List<HeaderElement> parsed = HeaderElement.parseList("application/json;profile=\"a,b\";q=0, text/plain");
            assertEquals(2, parsed.size());
            assertEquals("application/json", parsed.get(0).value());
            assertEquals(Map.of("profile", "a,b"), parsed.get(0).parameters());
            assertEquals(0.0, parsed.get(0).quality(), 0.0);
            assertEquals("text/plain", parsed.get(1).value());
        }

        @Test
        @DisplayName("a quoted semicolon does not split parameters")
        void quotedSemicolonDoesNotSplit() {
            HeaderElement element = only("a/b; x=\"1;q=0\"; y=2");
            assertEquals(Map.of("x", "1;q=0", "y", "2"), element.parameters());
            assertEquals(1.0, element.quality(), 0.0);
        }

        @Test
        @DisplayName("an escaped quote stays inside the quoted string and is unescaped")
        void escapedQuote() {
            HeaderElement element = only("a/b; x=\"he said \\\"hi, there\\\"\"; q=0.4");
            assertEquals("he said \"hi, there\"", element.parameters().get("x"));
            assertEquals(0.4, element.quality(), 0.0);
        }

        @Test
        @DisplayName("an escaped backslash before the closing quote still closes the string")
        void escapedBackslashBeforeClosingQuote() {
            List<HeaderElement> parsed = HeaderElement.parseList("a/b; x=\"c\\\\\", d/e");
            assertEquals(2, parsed.size());
            assertEquals("c\\", parsed.get(0).parameters().get("x"));
            assertEquals("d/e", parsed.get(1).value());
        }

        @Test
        @DisplayName("an empty quoted string is an empty value")
        void emptyQuotedString() {
            assertEquals("", only("a/b; x=\"\"").parameters().get("x"));
        }

        @Test
        @DisplayName("an unterminated quoted string skips its element, and the rest of the header with it")
        void unterminatedQuoteSkipsElement() {
            assertEquals(List.of("ok/one"), values("ok/one, a/b; x=\"oops, c/d"));
        }

        @Test
        @DisplayName("a final escaped quote leaves the string unterminated")
        void escapedFinalQuoteIsUnterminated() {
            assertEquals(List.of(), values("a/b; x=\"c\\\""));
        }

        @Test
        @DisplayName("a trailing backslash inside a quote is unterminated")
        void trailingBackslashInsideQuote() {
            assertEquals(List.of(), values("a/b; x=\"c\\"));
        }

        @Test
        @DisplayName("a lone quote is malformed")
        void loneQuote() {
            assertEquals(List.of(), values("\""));
            assertEquals(List.of(), values("a/b; \""));
        }

        @Test
        @DisplayName("an unterminated quote outside a parameter value is malformed and swallows the rest")
        void unterminatedQuoteOutsideParameterValue() {
            assertEquals(List.of(), values("\"a/b"));
            assertEquals(List.of("ok/one"), values("ok/one, a/b; flag\"x, c/d"));
        }

        @Test
        @DisplayName("characters after a closing quote make the element malformed")
        void charactersAfterClosingQuote() {
            assertEquals(List.of("c/d"), values("a/b; x=\"v\"junk, c/d"));
        }

        @Test
        @DisplayName("a quote inside a parameter name is malformed")
        void quoteInParameterName() {
            assertEquals(List.of("c/d"), values("a/b; x\"y\"=1, c/d"));
        }
    }

    @Nested
    @DisplayName("q parameter")
    class Quality {

        @Test
        @DisplayName("valid qvalue forms are accepted")
        void validForms() {
            assertEquals(0.0, only("a/b;q=0").quality(), 0.0);
            assertEquals(0.0, only("a/b;q=0.").quality(), 0.0);
            assertEquals(0.0, only("a/b;q=0.000").quality(), 0.0);
            assertEquals(0.5, only("a/b;q=0.5").quality(), 0.0);
            assertEquals(0.123, only("a/b;q=0.123").quality(), 0.0);
            assertEquals(1.0, only("a/b;q=1").quality(), 0.0);
            assertEquals(1.0, only("a/b;q=1.0").quality(), 0.0);
            assertEquals(1.0, only("a/b;q=1.000").quality(), 0.0);
        }

        @Test
        @DisplayName("the q parameter name is case-insensitive and never appears in parameters")
        void qNameIsCaseInsensitive() {
            HeaderElement element = only("a/b;Q=0.3;x=1");
            assertEquals(0.3, element.quality(), 0.0);
            assertEquals(Map.of("x", "1"), element.parameters());
        }

        @Test
        @DisplayName("invalid, out-of-range and over-precise q values make the element malformed")
        void invalidQualityIsMalformed() {
            for (String bad : List.of(
                    "",
                    "abc",
                    "2",
                    "1.5",
                    "1.001",
                    "-1",
                    "-0.5",
                    "0.1234",
                    ".5",
                    "+0.5",
                    "00",
                    "0.5.5",
                    "1e0",
                    "NaN",
                    "Infinity",
                    " ",
                    "0x1",
                    "١")) {
                assertEquals(List.of(), values("a/b;q=" + bad), "q=" + bad + " must be malformed");
            }
        }

        @Test
        @DisplayName("a quoted q is malformed")
        void quotedQualityIsMalformed() {
            assertEquals(List.of(), values("a/b;q=\"0\""));
            assertEquals(List.of(), values("a/b;q=\"1\""));
        }

        @Test
        @DisplayName("a malformed element is skipped and its neighbours are kept")
        void malformedQualitySkipsOnlyThatElement() {
            assertEquals(List.of("a/b", "e/f"), values("a/b, c/d;q=nope, e/f;q=0.2"));
        }
    }

    @Nested
    @DisplayName("empty input and empty elements")
    class Empties {

        @Test
        @DisplayName("null and blank input yield an empty list")
        void nullAndBlank() {
            assertEquals(List.of(), HeaderElement.parseList(null));
            assertEquals(List.of(), HeaderElement.parseList(""));
            assertEquals(List.of(), HeaderElement.parseList("   "));
        }

        @Test
        @DisplayName("empty elements and a trailing comma are skipped")
        void emptyElementsSkipped() {
            assertEquals(List.of("a/b", "c/d"), values(",, a/b ,, , c/d,"));
        }

        @Test
        @DisplayName("an element with an empty value is skipped")
        void emptyValueSkipped() {
            assertEquals(List.of("c/d"), values(";q=0.5, c/d"));
        }
    }

    @Nested
    @DisplayName("element cap")
    class Cap {

        @Test
        @DisplayName("only the first 50 non-empty elements are considered")
        void capsAtFiftyElements() {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < 80; i++) {
                header.append("t/").append(i).append(',');
            }
            List<HeaderElement> parsed = HeaderElement.parseList(header.toString());
            assertEquals(HeaderElement.MAX_ELEMENTS, parsed.size());
            assertEquals(50, parsed.size());
            assertEquals("t/0", parsed.get(0).value());
            assertEquals("t/49", parsed.get(49).value());
        }

        @Test
        @DisplayName("malformed elements count toward the cap, empty ones do not")
        void malformedCountsEmptyDoesNot() {
            StringBuilder header = new StringBuilder(",,,,");
            for (int i = 0; i < 49; i++) {
                header.append("t/bad;q=9,,");
            }
            header.append("t/last,t/after");
            assertEquals(List.of("t/last"), values(header.toString()));
        }

        @Test
        @DisplayName("exactly 50 elements are all kept")
        void fiftyAreKept() {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                header.append(i == 0 ? "" : ",").append("t/").append(i);
            }
            assertEquals(50, HeaderElement.parseList(header.toString()).size());
        }
    }

    @Nested
    @DisplayName("single element parse")
    class Single {

        @Test
        @DisplayName("a comma is an ordinary character, not a separator")
        void commaIsOrdinary() {
            HeaderElement element = HeaderElement.parse("a/b, c/d; x=1");
            assertNotNull(element);
            assertEquals("a/b, c/d", element.value());
            assertEquals(Map.of("x", "1"), element.parameters());
        }

        @Test
        @DisplayName("null, blank and malformed input yield null")
        void nullBlankMalformed() {
            assertNull(HeaderElement.parse(null));
            assertNull(HeaderElement.parse("  "));
            assertNull(HeaderElement.parse("a/b; x=\"open"));
            assertNull(HeaderElement.parse("a/b; q=7"));
            assertNull(HeaderElement.parse(";x=1"));
        }
    }

    @Nested
    @DisplayName("record contract")
    class RecordContract {

        @Test
        @DisplayName("the constructor lowercases names, copies the map and keeps its order")
        void constructorNormalizes() {
            Map<String, String> source = new LinkedHashMap<>();
            source.put("B", "1");
            source.put("a", "2");
            HeaderElement element = new HeaderElement("v", source, 0.5);
            source.put("c", "3");
            assertEquals(List.of("b", "a"), new ArrayList<>(element.parameters().keySet()));
        }

        @Test
        @DisplayName("the constructor rejects null, a q parameter and an out-of-range quality")
        void constructorRejectsInvalid() {
            assertThrows(NullPointerException.class, () -> new HeaderElement(null, Map.of(), 1.0));
            assertThrows(NullPointerException.class, () -> new HeaderElement("v", null, 1.0));
            assertThrows(IllegalArgumentException.class, () -> new HeaderElement("v", Map.of("Q", "1"), 1.0));
            assertThrows(IllegalArgumentException.class, () -> new HeaderElement("v", Map.of(), 1.1));
            assertThrows(IllegalArgumentException.class, () -> new HeaderElement("v", Map.of(), -0.1));
            assertThrows(IllegalArgumentException.class, () -> new HeaderElement("v", Map.of(), Double.NaN));
        }
    }

    @Nested
    @DisplayName("duplicate q and bounded work")
    class Bounds {

        private long allocatedBytesOf(Runnable work) {
            com.sun.management.ThreadMXBean mx =
                    (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            long id = Thread.currentThread().getId();
            long before = mx.getThreadAllocatedBytes(id);
            work.run();
            return mx.getThreadAllocatedBytes(id) - before;
        }

        @Test
        @DisplayName("a repeated q makes the element malformed")
        void repeatedQualityIsMalformed() {
            assertEquals(List.of(), values("application/json;q=0;q=1"));
            assertNull(HeaderElement.parse("application/json;q=0.5;Q=0.7"));
            assertEquals(List.of("a/b"), values("application/json;q=0;q=1, a/b"));
        }

        @Test
        @DisplayName("32 parameters are accepted and 33 make the element malformed")
        void parameterCap() {
            StringBuilder ok = new StringBuilder("a/b");
            for (int i = 0; i < 32; i++) {
                ok.append(";p").append(i).append("=1");
            }
            assertEquals(32, only(ok.toString()).parameters().size());
            assertNull(HeaderElement.parse(ok + ";p32=1"));
            assertEquals(List.of("c/d"), values(ok + ";p32=1, c/d"));
        }

        @Test
        @DisplayName("empty parameters do not count toward the parameter cap")
        void emptyParametersDoNotCount() {
            StringBuilder header = new StringBuilder("a/b");
            for (int i = 0; i < 100; i++) {
                header.append(';');
            }
            assertEquals(List.of("a/b"), values(header.toString()));
        }

        @Test
        @DisplayName("a header of a million commas is read without allocating per comma")
        void millionCommas() {
            String header = ",".repeat(1_000_000);
            long allocated = allocatedBytesOf(
                    () -> org.junit.jupiter.api.Assertions.assertEquals(List.of(), HeaderElement.parseList(header)));
            assertTrue(allocated < 256 * 1024, "allocated " + allocated + " bytes");
        }

        @Test
        @DisplayName("an element with ten thousand parameters is rejected without materializing them")
        void tenThousandParameters() {
            StringBuilder header = new StringBuilder("a/b");
            for (int i = 0; i < 10_000; i++) {
                header.append(";p").append(i).append("=1");
            }
            String text = header.toString();
            long allocated =
                    allocatedBytesOf(() -> org.junit.jupiter.api.Assertions.assertNull(HeaderElement.parse(text)));
            assertTrue(allocated < 128 * 1024, "allocated " + allocated + " bytes");
            long listAllocated = allocatedBytesOf(
                    () -> org.junit.jupiter.api.Assertions.assertEquals(List.of(), HeaderElement.parseList(text)));
            assertTrue(listAllocated < 128 * 1024, "allocated " + listAllocated + " bytes");
        }

        @Test
        @DisplayName("scanning stops at the fiftieth non-empty element")
        void stopsAtFiftiethElement() {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                header.append("t/").append(i).append(',');
            }
            header.append("\"unterminated").append(",".repeat(500_000));
            String text = header.toString();
            long allocated = allocatedBytesOf(() -> org.junit.jupiter.api.Assertions.assertEquals(
                    50, HeaderElement.parseList(text).size()));
            assertTrue(allocated < 512 * 1024, "allocated " + allocated + " bytes");
        }
    }

    @Nested
    @DisplayName("shared tokenizer")
    class Tokenizer {

        @Test
        @DisplayName("splitOutsideQuotes splits on the delimiter and keeps segments untrimmed")
        void splitsOnDelimiter() {
            assertEquals(List.of("a", " b", "c "), HeaderElement.splitOutsideQuotes("a; b;c ", ';'));
        }

        @Test
        @DisplayName("splitOutsideQuotes keeps empty segments, including the one after a trailing delimiter")
        void keepsEmptySegments() {
            assertEquals(List.of("a", "", "b", ""), HeaderElement.splitOutsideQuotes("a,,b,", ','));
            assertEquals(List.of(""), HeaderElement.splitOutsideQuotes("", ','));
        }

        @Test
        @DisplayName("splitOutsideQuotes does not split on a delimiter inside a quoted string")
        void keepsQuotedDelimiter() {
            assertEquals(
                    List.of("a=\"x,y\"", " b=\"z;w\""), HeaderElement.splitOutsideQuotes("a=\"x,y\", b=\"z;w\"", ','));
        }

        @Test
        @DisplayName("splitOutsideQuotes treats an escaped quote as part of the quoted string")
        void escapedQuoteDoesNotEndQuotedString() {
            assertEquals(List.of("a=\"x\\\",y\"", "b"), HeaderElement.splitOutsideQuotes("a=\"x\\\",y\",b", ','));
        }

        @Test
        @DisplayName("splitOutsideQuotes treats a backslash outside a quoted string as literal")
        void backslashOutsideQuotesIsLiteral() {
            assertEquals(List.of("a\\", "b"), HeaderElement.splitOutsideQuotes("a\\,b", ','));
        }

        @Test
        @DisplayName("splitOutsideQuotes returns null for an unterminated quoted string")
        void unterminatedQuoteIsNull() {
            assertNull(HeaderElement.splitOutsideQuotes("a=\"x,y", ','));
        }

        @Test
        @DisplayName("splitOutsideQuotes returns null when a trailing backslash escapes the closing quote")
        void escapedClosingQuoteIsNull() {
            assertNull(HeaderElement.splitOutsideQuotes("a=\"x\\\"", ','));
        }

        @Test
        @DisplayName("splitOutsideQuotes rejects a quote or backslash delimiter and a null text")
        void rejectsBadArguments() {
            assertThrows(IllegalArgumentException.class, () -> HeaderElement.splitOutsideQuotes("a", '"'));
            assertThrows(IllegalArgumentException.class, () -> HeaderElement.splitOutsideQuotes("a", '\\'));
            assertThrows(NullPointerException.class, () -> HeaderElement.splitOutsideQuotes(null, ','));
        }

        @Test
        @DisplayName("splitOutsideQuotes returns an unmodifiable list")
        void resultIsUnmodifiable() {
            List<String> segments = HeaderElement.splitOutsideQuotes("a,b", ',');
            assertThrows(UnsupportedOperationException.class, () -> segments.add("c"));
        }

        @Test
        @DisplayName("unquote returns a value that does not start with a quote unchanged")
        void unquoteLeavesPlainValue() {
            assertEquals("utf-8", HeaderElement.unquote("utf-8"));
            assertEquals("", HeaderElement.unquote(""));
            assertEquals("a\"b", HeaderElement.unquote("a\"b"));
        }

        @Test
        @DisplayName("unquote removes the quotes and resolves backslash escapes")
        void unquoteResolvesEscapes() {
            assertEquals("a,b", HeaderElement.unquote("\"a,b\""));
            assertEquals("a\"b", HeaderElement.unquote("\"a\\\"b\""));
            assertEquals("a\\b", HeaderElement.unquote("\"a\\\\b\""));
            assertEquals("", HeaderElement.unquote("\"\""));
        }

        @Test
        @DisplayName("unquote returns null for a quoted string that is not complete")
        void unquoteRejectsMalformed() {
            assertNull(HeaderElement.unquote("\""));
            assertNull(HeaderElement.unquote("\"abc"));
            assertNull(HeaderElement.unquote("\"abc\\"));
            assertNull(HeaderElement.unquote("\"abc\\\""));
            assertNull(HeaderElement.unquote("\"abc\"def"));
            assertNull(HeaderElement.unquote("\"a\"b\""));
        }

        @Test
        @DisplayName("unquote rejects null")
        void unquoteRejectsNull() {
            assertThrows(NullPointerException.class, () -> HeaderElement.unquote(null));
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        @DisplayName("odd inputs never throw")
        void oddInputsNeverThrow() {
            for (String odd : List.of(
                    "\"",
                    "\\",
                    "\"\\",
                    ",",
                    ";",
                    "=",
                    ";;;",
                    ",,,",
                    "\"\"\"",
                    "a/b;\"",
                    "a/b;x=\"",
                    "a/b;x=\\",
                    "a/b;x=\"\\",
                    "q=1",
                    ";q=",
                    "a/b;q",
                    "a/b;=",
                    "\u0000",
                    "\ud800",
                    "a/b;x=\"\\\"\\\"\\")) {
                assertDoesNotThrow(() -> HeaderElement.parseList(odd), "parseList: " + odd);
                assertDoesNotThrow(() -> HeaderElement.parse(odd), "parse: " + odd);
            }
        }

        @Test
        @DisplayName("a very long header is handled without throwing")
        void hugeInputNeverThrows() {
            String megabyte = "a/b;x=\"y,".repeat(100_000);
            assertDoesNotThrow(() -> HeaderElement.parseList(megabyte));
            String commas = ",".repeat(1_000_000);
            assertEquals(List.of(), HeaderElement.parseList(commas));
            String longValue = "a/".concat("b".repeat(1_000_000));
            assertEquals(1, HeaderElement.parseList(longValue).size());
            String manyEscapes = "a/b;x=\"" + "\\\\".repeat(500_000);
            assertEquals(List.of(), HeaderElement.parseList(manyEscapes));
        }

        @Test
        @DisplayName("random strings over the grammar's metacharacters never throw and keep invariants")
        void randomInputsKeepInvariants() {
            String alphabet = ",;=\"\\ aq/.01-*";
            Random random = new Random(20260401L);
            for (int round = 0; round < 5_000; round++) {
                int length = random.nextInt(round % 50 == 0 ? 4_000 : 40);
                StringBuilder sb = new StringBuilder(length);
                for (int i = 0; i < length; i++) {
                    sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                String input = sb.toString();
                List<HeaderElement> parsed = assertDoesNotThrow(() -> HeaderElement.parseList(input), input);
                assertTrue(parsed.size() <= HeaderElement.MAX_ELEMENTS);
                for (HeaderElement element : parsed) {
                    assertTrue(element.quality() >= 0.0 && element.quality() <= 1.0, input);
                    assertTrue(
                            !element.value().isEmpty()
                                    && element.value().equals(element.value().trim()),
                            input);
                    assertTrue(!element.parameters().containsKey("q"), input);
                }
            }
        }
    }
}
