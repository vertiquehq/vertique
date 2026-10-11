// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.request.MediaType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MediaType} parsing, compatibility, specificity, and equality.
 */
class MediaTypeTest {

    // --- Parsing ---

    @Test
    @DisplayName("parse simple type returns correct type and subtype")
    void shouldParseSimpleType() {
        MediaType mt = MediaType.parse("application/json");
        assertNotNull(mt);
        assertEquals("application", mt.type());
        assertEquals("json", mt.subtype());
        assertTrue(mt.parameters().isEmpty());
        assertEquals(1.0, mt.qualityFactor(), 0.0001);
    }

    @Test
    @DisplayName("parse with parameters extracts charset and sets quality 1.0")
    void shouldParseWithParameters() {
        MediaType mt = MediaType.parse("text/html; charset=utf-8");
        assertNotNull(mt);
        assertEquals("text", mt.type());
        assertEquals("html", mt.subtype());
        assertEquals("utf-8", mt.parameters().get("charset"));
        assertEquals(1.0, mt.qualityFactor(), 0.0001);
    }

    @Test
    @DisplayName("parse with q-value extracts quality factor and excludes q from parameters")
    void shouldParseWithQValue() {
        MediaType mt = MediaType.parse("application/xml;q=0.9");
        assertNotNull(mt);
        assertEquals("application", mt.type());
        assertEquals("xml", mt.subtype());
        assertEquals(0.9, mt.qualityFactor(), 0.0001);
        assertFalse(mt.parameters().containsKey("q"));
    }

    @Test
    @DisplayName("parse with whitespace around separators parses correctly")
    void shouldParseWithWhitespace() {
        MediaType mt = MediaType.parse("application/json ; q=0.8 ; charset=utf-8");
        assertNotNull(mt);
        assertEquals("application", mt.type());
        assertEquals("json", mt.subtype());
        assertEquals(0.8, mt.qualityFactor(), 0.0001);
        assertEquals("utf-8", mt.parameters().get("charset"));
        assertFalse(mt.parameters().containsKey("q"));
    }

    @Test
    @DisplayName("parse null returns null")
    void shouldReturnNullForNullInput() {
        assertNull(MediaType.parse(null));
    }

    @Test
    @DisplayName("parse blank returns null")
    void shouldReturnNullForBlankInput() {
        assertNull(MediaType.parse("   "));
    }

    @Test
    @DisplayName("parse without slash returns null")
    void shouldReturnNullForMissingSlash() {
        assertNull(MediaType.parse("applicationjson"));
    }

    // --- Wildcard detection ---

    @Test
    @DisplayName("isWildcardType is true for */*")
    void shouldDetectWildcardType() {
        MediaType mt = MediaType.parse("*/*");
        assertNotNull(mt);
        assertTrue(mt.isWildcardType());
        assertTrue(mt.isWildcardSubtype());
    }

    @Test
    @DisplayName("isWildcardSubtype is true for application/*")
    void shouldDetectWildcardSubtype() {
        MediaType mt = MediaType.parse("application/*");
        assertNotNull(mt);
        assertFalse(mt.isWildcardType());
        assertTrue(mt.isWildcardSubtype());
    }

    @Test
    @DisplayName("isWildcardType and isWildcardSubtype are false for concrete type")
    void shouldNotDetectWildcardForConcreteType() {
        MediaType mt = MediaType.parse("application/json");
        assertNotNull(mt);
        assertFalse(mt.isWildcardType());
        assertFalse(mt.isWildcardSubtype());
    }

    // --- Compatibility ---

    @Test
    @DisplayName("*/* is compatible with application/json")
    void wildcardTypeMatchesEverything() {
        MediaType wildcard = MediaType.parse("*/*");
        MediaType appJson = MediaType.parse("application/json");
        assertNotNull(wildcard);
        assertNotNull(appJson);
        assertTrue(wildcard.isCompatible(appJson));
        assertTrue(appJson.isCompatible(wildcard));
    }

    @Test
    @DisplayName("a wildcard type with a concrete subtype is malformed, not a full wildcard")
    void wildcardTypeWithConcreteSubtypeIsMalformed() {
        assertNull(MediaType.parse("*/xml"));
        assertNull(MediaType.parse("*/json;q=0.5"));
        assertNull(MediaType.valueOf("*/xml; charset=utf-8"));
    }

    @Test
    @DisplayName("a directly constructed */xml matches no concrete type")
    void constructedWildcardTypeWithConcreteSubtypeMatchesNothingConcrete() {
        MediaType wildcardType = new MediaType("*", "xml", Map.of(), 1.0);
        MediaType appXml = MediaType.parse("application/xml");
        MediaType textPlain = MediaType.parse("text/plain");
        assertNotNull(appXml);
        assertNotNull(textPlain);
        assertFalse(wildcardType.isCompatible(appXml));
        assertFalse(appXml.isCompatible(wildcardType));
        assertFalse(wildcardType.isCompatible(textPlain));
        assertFalse(textPlain.isCompatible(wildcardType));
    }

    @Test
    @DisplayName("application/* is compatible with application/json")
    void wildcardSubtypeMatchesApplicationTypes() {
        MediaType appWild = MediaType.parse("application/*");
        MediaType appJson = MediaType.parse("application/json");
        assertNotNull(appWild);
        assertNotNull(appJson);
        assertTrue(appWild.isCompatible(appJson));
        assertTrue(appJson.isCompatible(appWild));
    }

    @Test
    @DisplayName("application/* does not match text/plain")
    void wildcardSubtypeDoesNotMatchDifferentType() {
        MediaType appWild = MediaType.parse("application/*");
        MediaType textPlain = MediaType.parse("text/plain");
        assertNotNull(appWild);
        assertNotNull(textPlain);
        assertFalse(appWild.isCompatible(textPlain));
    }

    @Test
    @DisplayName("text/plain does not match application/json")
    void differentTypesAreNotCompatible() {
        MediaType textPlain = MediaType.parse("text/plain");
        MediaType appJson = MediaType.parse("application/json");
        assertNotNull(textPlain);
        assertNotNull(appJson);
        assertFalse(textPlain.isCompatible(appJson));
    }

    @Test
    @DisplayName("application/json is compatible with itself")
    void exactMatchIsCompatible() {
        MediaType a = MediaType.parse("application/json");
        MediaType b = MediaType.parse("application/json");
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(a.isCompatible(b));
    }

    // --- Specificity ---

    @Test
    @DisplayName("specificity ordering: */* < application/* < application/json < application/json;charset=utf-8")
    void shouldOrderBySpecificity() {
        MediaType wildcard = MediaType.parse("*/*");
        MediaType typeWild = MediaType.parse("application/*");
        MediaType concrete = MediaType.parse("application/json");
        MediaType withParams = MediaType.parse("application/json;charset=utf-8");

        assertNotNull(wildcard);
        assertNotNull(typeWild);
        assertNotNull(concrete);
        assertNotNull(withParams);

        assertTrue(wildcard.specificity() < typeWild.specificity());
        assertTrue(typeWild.specificity() < concrete.specificity());
        assertTrue(concrete.specificity() < withParams.specificity());
    }

    @Test
    @DisplayName("specificity values are 0, 1, 2, 3")
    void shouldReturnExpectedSpecificityValues() {
        assertEquals(0, MediaType.parse("*/*").specificity());
        assertEquals(1, MediaType.parse("application/*").specificity());
        assertEquals(2, MediaType.parse("application/json").specificity());
        assertEquals(3, MediaType.parse("application/json;charset=utf-8").specificity());
    }

    // --- withoutParameters ---

    @Test
    @DisplayName("withoutParameters returns lowercase type/subtype without params")
    void shouldReturnWithoutParameters() {
        MediaType mt = MediaType.parse("text/HTML; charset=utf-8");
        assertNotNull(mt);
        assertEquals("text/html", mt.withoutParameters());
    }

    @Test
    @DisplayName("withoutParameters on simple type returns same string")
    void shouldReturnWithoutParametersForSimpleType() {
        MediaType mt = MediaType.parse("application/json");
        assertNotNull(mt);
        assertEquals("application/json", mt.withoutParameters());
    }

    // --- Case insensitivity ---

    @Test
    @DisplayName("parse stores type and subtype in lowercase")
    void shouldStoreLowercase() {
        MediaType mt = MediaType.parse("Application/JSON");
        assertNotNull(mt);
        assertEquals("application", mt.type());
        assertEquals("json", mt.subtype());
    }

    @Test
    @DisplayName("isCompatible is case-insensitive for type and subtype")
    void shouldBeCaseInsensitiveForCompatibility() {
        MediaType a = MediaType.parse("Application/JSON");
        MediaType b = MediaType.parse("application/json");
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(a.isCompatible(b));
    }

    // --- equals / hashCode ---

    @Test
    @DisplayName("equal media types have equal hashCodes")
    void equalTypesShouldHaveEqualHashCodes() {
        MediaType a = MediaType.parse("application/json");
        MediaType b = MediaType.parse("application/json");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("media types with same type/subtype but different params are not equal")
    void differentParamsMeansNotEqual() {
        MediaType a = MediaType.parse("application/json;charset=utf-8");
        MediaType b = MediaType.parse("application/json");
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("media types with same type/subtype but different quality factors are still equal")
    void differentQualityFactorDoesNotAffectEquality() {
        MediaType a = MediaType.parse("application/json;q=0.8");
        MediaType b = MediaType.parse("application/json;q=1.0");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a, b);
    }

    // --- valueOf alias ---

    @Test
    @DisplayName("valueOf is an alias for parse")
    void valueOfDelegatesToParse() {
        MediaType fromParse = MediaType.parse("application/json");
        MediaType fromValueOf = MediaType.valueOf("application/json");
        assertEquals(fromParse, fromValueOf);
    }

    // --- Quote-aware parsing ---

    @Test
    @DisplayName("quoted semicolon and comma stay inside the parameter value, which is returned unquoted")
    void quotedParameterValueIsUnquoted() {
        MediaType mt = MediaType.parse("application/json;profile=\"a;b,c\";q=0.5");
        assertNotNull(mt);
        assertEquals("a;b,c", mt.parameters().get("profile"));
        assertEquals(0.5, mt.qualityFactor(), 0.0001);
    }

    @Test
    @DisplayName("escaped characters inside a quoted parameter value are unescaped")
    void quotedParameterValueIsUnescaped() {
        MediaType mt = MediaType.parse("text/plain;title=\"say \\\"hi\\\"\"");
        assertNotNull(mt);
        assertEquals("say \"hi\"", mt.parameters().get("title"));
    }

    @Test
    @DisplayName("an unterminated quoted string makes the media type invalid")
    void unterminatedQuoteReturnsNull() {
        assertNull(MediaType.parse("application/json;profile=\"a,b"));
    }

    @Test
    @DisplayName(
            "an invalid, quoted or out-of-range q is not malformed: it is clamped or ignored as for a Content-Type")
    void parseKeepsLenientQualityHandling() {
        assertQuality(1.0, "application/json;q=abc");
        assertQuality(1.0, "application/json;q=\"0\"");
        assertQuality(1.0, "application/json;q=\"\"");
        assertQuality(1.0, "application/json;Q=x");
        assertQuality(1.0, "application/json;q=2");
        assertQuality(1.0, "application/json;q=1.5");
        assertQuality(0.0, "application/json;q=-1");
        assertQuality(0.5, "application/json;q=.5");
        assertQuality(0.1234, "application/json;q=0.1234");
        assertQuality(0.3, "application/json;q=0.3;q=abc");
        assertQuality(0.7, "application/json;q=0.3;Q=0.7");
        assertQuality(1.0, "application/json;q=NaN");
    }

    @Test
    @DisplayName("a media type with an unusable q still splits and unquotes its other parameters")
    void lenientQualityKeepsQuoteAwareParameters() {
        MediaType mt = MediaType.parse("image/png; q=abc; profile=\"a;b\"; charset=utf-8");
        assertNotNull(mt);
        assertEquals(java.util.Map.of("profile", "a;b", "charset", "utf-8"), mt.parameters());
        assertEquals(1.0, mt.qualityFactor(), 0.0);
    }

    @Test
    @DisplayName("an unterminated quote, a quote followed by more characters and too many parameters are still invalid")
    void structuralMalformationStillReturnsNull() {
        assertNull(MediaType.parse("image/png; x=\""));
        assertNull(MediaType.parse("image/png; x=\"a\"b"));
        StringBuilder many = new StringBuilder("image/png");
        for (int i = 0; i < 33; i++) {
            many.append(";p").append(i).append("=1");
        }
        assertNull(MediaType.parse(many.toString()));
    }

    private static void assertQuality(double expected, String raw) {
        MediaType mt = MediaType.parse(raw);
        assertNotNull(mt, raw);
        assertEquals(expected, mt.qualityFactor(), 0.0, raw);
        assertTrue(mt.parameters().isEmpty(), raw);
    }

    @Test
    @DisplayName("a comma outside quotes is not a separator for a single media type")
    void commaIsNotSeparatorForSingleMediaType() {
        MediaType mt = MediaType.parse("application/json, text/plain");
        assertNotNull(mt);
        assertEquals("application", mt.type());
        assertEquals("json, text/plain", mt.subtype());
    }

    @Test
    @DisplayName("toString quotes parameter values that are not tokens so the output parses back")
    void toStringQuotesNonTokenValues() {
        MediaType mt = MediaType.parse("application/json;profile=\"a,b\";charset=utf-8;note=\"\\\"x\\\"\"");
        assertNotNull(mt);
        String text = mt.toString();
        assertEquals("application/json;profile=\"a,b\";charset=utf-8;note=\"\\\"x\\\"\"", text);
        assertEquals(mt.parameters(), MediaType.parse(text).parameters());
    }

    @Test
    @DisplayName("toString quotes an empty parameter value")
    void toStringQuotesEmptyValue() {
        assertEquals("a/b;x=\"\"", MediaType.parse("a/b;x=\"\"").toString());
    }

    @Test
    @DisplayName("toString replaces CR, LF and other control characters in a quoted parameter value with an underscore")
    void toStringReplacesControlCharacters() {
        MediaType mt = new MediaType(
                "text", "plain", java.util.Map.of("note", "a\r\nb" + (char) 1 + "c" + (char) 127 + "d\te"), 1.0);
        assertEquals("text/plain;note=\"a__b_c_d\te\"", mt.toString());
    }

    @Test
    @DisplayName("toString keeps a value with a control character distinct from the value without it")
    void toStringKeepsDistinctValuesDistinct() {
        MediaType withControl =
                new MediaType("text", "plain", java.util.Map.of("filename", "evil.ph" + (char) 1 + "p"), 1.0);
        MediaType without = new MediaType("text", "plain", java.util.Map.of("filename", "evil.php"), 1.0);

        assertEquals("text/plain;filename=\"evil.ph_p\"", withControl.toString());
        assertNotEquals(without.toString(), withControl.toString());
        assertEquals(
                "evil.ph_p",
                MediaType.parse(withControl.toString()).parameters().get("filename"));
    }

    @Test
    @DisplayName("type and subtype are lowercased without regard to the default locale")
    void typeAndSubtypeLowercasedWithRootLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"));
        try {
            MediaType parsed = MediaType.parse("IMAGE/TIFF; Charset=UTF-8");
            assertNotNull(parsed);
            assertEquals("image", parsed.type());
            assertEquals("tiff", parsed.subtype());
            assertEquals("image/tiff", parsed.withoutParameters());
            assertEquals("image/tiff", new MediaType("IMAGE", "TIFF", java.util.Map.of(), 1.0).withoutParameters());
            assertTrue(parsed.isCompatible(MediaType.parse("image/tiff")));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("the constructor rejects a control character in the type, subtype or a parameter name")
    void constructorRejectsControlCharacters() {
        java.util.Map<String, String> none = java.util.Map.of();
        assertThrows(IllegalArgumentException.class, () -> new MediaType("te\r\nxt", "plain", none, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new MediaType("text", "pl\nain", none, 1.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MediaType("text", "plain", java.util.Map.of("a\r\nSet-Cookie: x", "v"), 1.0));
        assertThrows(IllegalArgumentException.class, () -> new MediaType("text", "pl" + (char) 127 + "ain", none, 1.0));
    }

    @Test
    @DisplayName(
            "parse returns null instead of throwing for a control character in the type, subtype or a parameter name")
    void parseReturnsNullForControlCharacters() {
        assertNull(MediaType.parse("te" + (char) 1 + "xt/plain"));
        assertNull(MediaType.parse("text/pl" + (char) 1 + "ain"));
        assertNull(MediaType.parse("text/plain;ch" + (char) 1 + "arset=utf-8"));
        assertNotNull(MediaType.parse("text/plain;charset=\"ut" + (char) 1 + "f\""));
    }
}
