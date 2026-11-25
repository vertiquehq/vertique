// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the variant translation of {@link JaxRsPathTemplate}. */
class JaxRsPathTemplateTest {

    @Test
    @DisplayName("a variant matches only what the full template matches, with the alternative text exact")
    void variantIsSubsetOfFullTemplate() {
        String full = "/x/{p:b/admin | [^/]+}";
        Pattern fullPattern = Pattern.compile(JaxRsPathTemplate.translate(full).vertxValue());
        Pattern variant = Pattern.compile(
                JaxRsPathTemplate.translateVariant(full, "/x/{p:b/admin }").vertxValue());

        assertFalse(fullPattern.matcher("/x/b/admin").matches());
        assertFalse(variant.matcher("/x/b/admin").matches());
        assertTrue(fullPattern.matcher("/x/b/admin ").matches());
        assertTrue(variant.matcher("/x/b/admin ").matches());
    }

    @Test
    @DisplayName("a variant binds exactly the groups the full template binds")
    void variantBindsLikeFullTemplate() {
        String full = "/{x:.+}/{p:a/b|b}";
        Pattern fullPattern = Pattern.compile(JaxRsPathTemplate.translate(full).vertxValue());
        Pattern variant = Pattern.compile(
                JaxRsPathTemplate.translateVariant(full, "/{x:.+}/{p:a/b}").vertxValue());

        Matcher expected = fullPattern.matcher("/q/a/b");
        Matcher actual = variant.matcher("/q/a/b");
        assertTrue(expected.matches());
        assertTrue(actual.matches());
        assertEquals("q/a", actual.group("x"));
        assertEquals("b", actual.group("p"));
        assertEquals(expected.group("x"), actual.group("x"));
        assertEquals(expected.group("p"), actual.group("p"));
        assertEquals(fullPattern.namedGroups(), variant.namedGroups());
    }
}
