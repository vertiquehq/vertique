// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.request.MediaType;
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
}
