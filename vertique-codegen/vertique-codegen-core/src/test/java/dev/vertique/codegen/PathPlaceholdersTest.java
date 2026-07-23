// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PathPlaceholders}.
 *
 * <p>Verifies placeholder extraction from JAX-RS path templates, including simple names,
 * regex-constrained placeholders (including those whose constraint contains {@code /}),
 * ordering, deduplication, and edge cases.
 */
class PathPlaceholdersTest {

    @Test
    @DisplayName("simple {name} placeholder is extracted")
    void simplePlaceholder_extracted() {
        Set<String> result = PathPlaceholders.extract("/users/{name}");
        assertEquals(Set.of("name"), result);
    }

    @Test
    @DisplayName("regex-constrained {id:[0-9]+} strips the :regex suffix")
    void regexConstrainedPlaceholder_stripsToName() {
        Set<String> result = PathPlaceholders.extract("/users/{id:[0-9]+}");
        assertEquals(Set.of("id"), result);
    }

    @Test
    @DisplayName("multiple placeholders preserve insertion order")
    void multiplePlaceholders_preserveOrder() {
        Set<String> result = PathPlaceholders.extract("/a/{first}/b/{second}/c/{third}");
        // LinkedHashSet preserves insertion order; verify element order via iterator
        assertEquals(java.util.List.of("first", "second", "third"), java.util.List.copyOf(result));
    }

    @Test
    @DisplayName("empty path returns empty set")
    void emptyPath_returnsEmptySet() {
        assertTrue(PathPlaceholders.extract("").isEmpty());
    }

    @Test
    @DisplayName("path with no placeholders returns empty set")
    void noPlaceholders_returnsEmptySet() {
        assertTrue(PathPlaceholders.extract("/users/profile").isEmpty());
    }

    @Test
    @DisplayName("{a/b} with literal slash in name — extracted as-is (runtime-aligned grammar)")
    void placeholderWithSlashInName_extractedAsIs() {
        // The new pattern mirrors SimpleUriBuilder.TEMPLATE_NAME_PATTERN which allows '/' inside
        // the name group (it only excludes ':' and '}'). So {a/b} is a malformed template but the
        // name group extracts "a/b". This matches what the runtime does with the same grammar.
        Set<String> result = PathPlaceholders.extract("/{a/b}");
        assertEquals(Set.of("a/b"), result);
    }

    @Test
    @DisplayName("adjacent {a}{b} placeholders are both extracted")
    void adjacentPlaceholders_bothExtracted() {
        Set<String> result = PathPlaceholders.extract("/{a}{b}");
        assertEquals(java.util.List.of("a", "b"), java.util.List.copyOf(result));
    }

    @Test
    @DisplayName("duplicate {a}/{a} is deduplicated to a single entry")
    void duplicatePlaceholder_deduplicated() {
        Set<String> result = PathPlaceholders.extract("/{a}/sub/{a}");
        assertEquals(Set.of("a"), result);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("mixed regex and plain placeholders both extracted with stripping")
    void mixedRegexAndPlain_bothExtracted() {
        Set<String> result = PathPlaceholders.extract("/orders/{orderId:[0-9]+}/items/{itemName}");
        assertEquals(java.util.List.of("orderId", "itemName"), java.util.List.copyOf(result));
    }

    @Test
    @DisplayName("whitespace inside braces is trimmed — { id } becomes id")
    void whitespaceInside_trimmed() {
        // Runtime path-template parser accepts { id } as a placeholder named "id".
        Set<String> result = PathPlaceholders.extract("/{ id }/{ name : [a-z]+ }");
        assertEquals(java.util.List.of("id", "name"), java.util.List.copyOf(result));
    }

    // --- Fix B: regex constraint containing '/' ---

    @Test
    @DisplayName("{id:[^/]+} — regex constraint with '/' is handled correctly (Fix B)")
    void regexConstraintWithSlash_extractsName() {
        // Fix B: old regex \\{([^/}]+)} excluded ANY '/' inside braces, so {id:[^/]+} produced
        // no match (the '[^/]+' literal in the constraint was rejected). The new grammar allows
        // '/' inside the optional ':regex' suffix.
        Set<String> result = PathPlaceholders.extract("/users/{id:[^/]+}");
        assertEquals(Set.of("id"), result);
    }

    @Test
    @DisplayName("{path:.*/sub} — regex constraint with '/' path-greedy pattern (Fix B)")
    void regexConstraintWithSlashPath_extractsName() {
        Set<String> result = PathPlaceholders.extract("/files/{path:.*/sub}");
        assertEquals(Set.of("path"), result);
    }

    @Test
    @DisplayName("multiple placeholders, one with '/' in regex constraint — all extracted (Fix B)")
    void multipleWithRegexSlash_allExtracted() {
        Set<String> result = PathPlaceholders.extract("/a/{id:[^/]+}/b/{name}");
        assertEquals(java.util.List.of("id", "name"), java.util.List.copyOf(result));
    }
}
