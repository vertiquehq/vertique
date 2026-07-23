// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PackageResolver#longestCommonPrefix} — the core LCP algorithm that
 * determines the output package for generated modules.
 *
 * <p>The full {@code PackageResolver.resolve()} path (which uses a live {@link
 * javax.annotation.processing.ProcessingEnvironment}) is exercised indirectly via the
 * processor end-to-end tests in the consuming modules (CG-002 / CG-010).
 */
class PackageResolverTest {

    // --- Static LCP helper ---

    @Nested
    @DisplayName("longestCommonPrefix")
    class LongestCommonPrefixTests {

        @Test
        @DisplayName("identical packages return the same package")
        void identicalPackages_returnSame() {
            assertEquals("com.example.foo", PackageResolver.longestCommonPrefix("com.example.foo", "com.example.foo"));
        }

        @Test
        @DisplayName("two packages sharing a prefix return that prefix")
        void twoPackages_sharedPrefix_returnPrefix() {
            assertEquals("com.example", PackageResolver.longestCommonPrefix("com.example.foo", "com.example.bar"));
        }

        @Test
        @DisplayName("single-segment shared prefix is returned correctly")
        void singleSegmentSharedPrefix() {
            assertEquals("com", PackageResolver.longestCommonPrefix("com.foo", "com.bar"));
        }

        @Test
        @DisplayName("three-segment LCP is computed correctly")
        void threeSegmentLcp() {
            assertEquals(
                    "dev.vertique.examples",
                    PackageResolver.longestCommonPrefix("dev.vertique.examples.foo", "dev.vertique.examples.bar"));
        }

        @Test
        @DisplayName("disjoint packages return empty string")
        void disjointPackages_returnEmpty() {
            assertEquals("", PackageResolver.longestCommonPrefix("com.alpha", "org.beta"));
        }

        @Test
        @DisplayName("one package is a prefix of the other")
        void oneIsPrefix_returnsShortOne() {
            assertEquals("com.example", PackageResolver.longestCommonPrefix("com.example", "com.example.sub"));
        }

        @Test
        @DisplayName("packages share prefix as substring but not segment boundary")
        void prefixNotAtSegmentBoundary() {
            // "com.ex" is NOT a valid prefix of "com.example.foo" and "com.examiner.bar"
            // They share "com.exa" as a string prefix but the segment boundary is "com"
            assertEquals("com", PackageResolver.longestCommonPrefix("com.example.foo", "com.examiner.bar"));
        }
    }
}
