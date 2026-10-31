// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the encoders of the {@code correlated} dedup row in {@link WorkflowDedupScopes}: the namespace and the key are
 * 64 lowercase hex characters, stable for equal inputs, and different whenever a part of the namespace or the dedup key
 * differs, and neither holds its input.
 */
class WorkflowDedupScopesCorrelatedTest {

    private static final char UNIT_SEPARATOR = (char) 0x1F;

    /** The parts a namespace is built from. */
    record Parts(String contract, String signal, @Nullable String scope) {}

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(
                        "the contract differs",
                        new Parts("orders", "payment", "acme"),
                        new Parts("invoices", "payment", "acme"),
                        "d-1",
                        "d-2"),
                Arguments.of(
                        "the signal differs",
                        new Parts("orders", "payment", "acme"),
                        new Parts("orders", "refund", "acme"),
                        "d-1",
                        "D-1"),
                Arguments.of(
                        "the scope differs",
                        new Parts("orders", "payment", "acme"),
                        new Parts("orders", "payment", "globex"),
                        "d-1",
                        "d-1 "),
                Arguments.of(
                        "an absent scope differs from a present one",
                        new Parts("orders", "payment", null),
                        new Parts("orders", "payment", "acme"),
                        "d-1",
                        "d-1\u001fd-2"),
                Arguments.of("a, b is not ab, empty", new Parts("a", "b", null), new Parts("ab", "", null), "a", "ab"),
                Arguments.of(
                        "a boundary moved between signal and scope",
                        new Parts("a", "b", "c"),
                        new Parts("a", "bc", null),
                        "x".repeat(1000),
                        "x".repeat(999)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the namespace and the key are stable hashes that separate their parts")
    void namespaceAndKeyAreStableHashesThatSeparateTheirParts(
            String description, Parts first, Parts second, String firstKey, String secondKey) {
        // Given two sets of parts that differ, and two dedup keys that differ
        // When the encoders run on each, and again on the first
        String firstNamespace = namespaceOf(first);
        String secondNamespace = namespaceOf(second);
        String firstHashed = WorkflowDedupScopes.correlatedKey(firstKey);
        String secondHashed = WorkflowDedupScopes.correlatedKey(secondKey);

        // Then each result is 64 lowercase hex characters
        assertThat(firstNamespace).matches("[0-9a-f]{64}");
        assertThat(secondNamespace).matches("[0-9a-f]{64}");
        assertThat(firstHashed).matches("[0-9a-f]{64}");
        assertThat(secondHashed).matches("[0-9a-f]{64}");

        // And equal inputs give the same result
        assertThat(namespaceOf(first)).isEqualTo(firstNamespace);
        assertThat(WorkflowDedupScopes.correlatedKey(firstKey)).isEqualTo(firstHashed);

        // And different parts give different namespaces, and different dedup keys different keys
        assertThat(firstNamespace).isNotEqualTo(secondNamespace);
        assertThat(firstHashed).isNotEqualTo(secondHashed);

        // And no result holds an input of a kind a hash could not contain by chance
        for (String input : new String[] {first.contract(), first.signal(), first.scope(), firstKey}) {
            if (input != null && input.length() > 3 && !input.matches("[0-9a-f]*")) {
                assertThat(firstNamespace).doesNotContain(input);
                assertThat(firstHashed).doesNotContain(input);
            }
        }

        // And the results are the SHA-256 of the parts joined by the unit separator, and of the dedup key
        assertThat(firstNamespace)
                .isEqualTo(sha256Hex(first.contract()
                        + UNIT_SEPARATOR
                        + first.signal()
                        + UNIT_SEPARATOR
                        + (first.scope() == null ? "" : first.scope())));
        assertThat(firstHashed).isEqualTo(sha256Hex(firstKey));
    }

    private static String namespaceOf(Parts parts) {
        return WorkflowDedupScopes.correlatedScope(parts.contract(), parts.signal(), parts.scope());
    }

    /** An oracle that does not share code with the encoders. */
    private static String sha256Hex(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
