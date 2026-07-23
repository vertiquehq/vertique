// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.codegen.support.Identifiers;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Identifiers}.
 */
class IdentifiersTest {

    // --- generatedClassName ---

    @Test
    void generatedClassName_topLevelType_appendsSuffix() {
        TypeElement type = mockTopLevel("Foo");
        assertEquals("FooModule", Identifiers.generatedClassName(type, "Module"));
    }

    @Test
    void generatedClassName_nestedType_walksEnclosingElements() {
        TypeElement outer = mockTopLevel("Outer");
        TypeElement inner = mockNested("Inner", outer);
        assertEquals("Outer_InnerModule", Identifiers.generatedClassName(inner, "Module"));
    }

    @Test
    void generatedClassName_doublyNestedType_walksFullChain() {
        TypeElement a = mockTopLevel("A");
        TypeElement b = mockNested("B", a);
        TypeElement c = mockNested("C", b);
        assertEquals("A_B_CFactory", Identifiers.generatedClassName(c, "Factory"));
    }

    @Test
    void generatedClassName_emptySuffix_returnsJustName() {
        TypeElement type = mockTopLevel("Foo");
        assertEquals("Foo", Identifiers.generatedClassName(type, ""));
    }

    // --- constantName ---

    @Test
    void constantName_camelCase_toScreamingSnake() {
        assertEquals("MY_FIELD", Identifiers.constantName("myField"));
    }

    @Test
    void constantName_pascalCase_toScreamingSnake() {
        assertEquals("GET_MY_VALUE", Identifiers.constantName("getMyValue"));
    }

    @Test
    void constantName_alreadyUppercase_unchanged() {
        assertEquals("MY_CONSTANT", Identifiers.constantName("MY_CONSTANT"));
    }

    @Test
    void constantName_hyphenatedInput_replacesHyphen() {
        assertEquals("MY_FIELD", Identifiers.constantName("my-field"));
    }

    // --- sanitize ---

    @Test
    void sanitize_validIdentifier_unchanged() {
        assertEquals("myField", Identifiers.sanitize("myField"));
    }

    @Test
    void sanitize_illegalChars_replacedWithUnderscore() {
        String result = Identifiers.sanitize("my-field.name");
        assertTrue(Character.isJavaIdentifierStart(result.charAt(0)), "First char must be a valid identifier start");
        for (int i = 0; i < result.length(); i++) {
            assertTrue(Character.isJavaIdentifierPart(result.charAt(i)), "Every char must be a valid identifier part");
        }
    }

    @Test
    void sanitize_leadingDigit_prependsUnderscore() {
        String result = Identifiers.sanitize("123abc");
        assertTrue(result.startsWith("_"), "Leading digit should be prefixed with underscore");
        assertFalse(Character.isDigit(result.charAt(0)));
    }

    @Test
    void sanitize_emptyInput_returnsValidIdentifier() {
        // "_" alone is a reserved keyword since Java 9, so the result must be longer.
        String result = Identifiers.sanitize("");
        assertEquals("__", result);
    }

    @Test
    void sanitize_keywordInput_appendsUnderscore() {
        assertEquals("class_", Identifiers.sanitize("class"));
        assertEquals("switch_", Identifiers.sanitize("switch"));
    }

    @Test
    void sanitize_loneUnderscoreInput_appendsUnderscore() {
        // "_" alone is a reserved keyword since Java 9.
        assertEquals("__", Identifiers.sanitize("_"));
    }

    // --- helpers ---

    private static TypeElement mockTopLevel(String simpleName) {
        TypeElement type = mock(TypeElement.class);
        Name simple = mock(Name.class);
        when(simple.toString()).thenReturn(simpleName);
        when(type.getSimpleName()).thenReturn(simple);
        when(type.getEnclosingElement()).thenReturn(mock(PackageElement.class));
        return type;
    }

    private static TypeElement mockNested(String simpleName, TypeElement enclosing) {
        TypeElement type = mock(TypeElement.class);
        Name simple = mock(Name.class);
        when(simple.toString()).thenReturn(simpleName);
        when(type.getSimpleName()).thenReturn(simple);
        when(type.getEnclosingElement()).thenReturn(enclosing);
        return type;
    }
}
