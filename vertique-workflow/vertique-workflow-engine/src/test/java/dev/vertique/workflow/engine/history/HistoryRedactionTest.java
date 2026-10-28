// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vertx.core.json.JsonObject;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies how the pointers a history redactor names are applied to a JSON tree: a value at a resolvable pointer is
 * replaced by a marker, a pointer that does not resolve changes nothing, and a pointer that is not a JSON Pointer is
 * refused without echoing any value.
 */
class HistoryRedactionTest {

    private static JsonObject redact(String tree, String... pointers) {
        return HistoryRedaction.apply(new JsonObject(tree), Set.of(pointers));
    }

    @Test
    @DisplayName("a resolvable pointer is replaced by the marker and a missing path leaves the tree unchanged")
    void replacesResolvableValuesAndIgnoresMissingPaths() {
        // Given a card number at an object path
        // When its pointer is redacted
        // Then only that value is replaced
        assertThat(redact("""
                {"card":{"number":"4111111111111111","holder":"A. Payer"},"ref":"r-1"}""", "/card/number")).as("an object value").isEqualTo(new JsonObject("""
                        {"card":{"number":"[redacted]","holder":"A. Payer"},"ref":"r-1"}"""));

        // Given two cards in an array
        // When the pointer names the number of the second by its index
        // Then only the second number is replaced
        assertThat(redact("""
                {"cards":[{"number":"4111111111111111"},{"number":"5500000000000004"}]}""", "/cards/1/number")).as("a nested array element").isEqualTo(new JsonObject("""
                        {"cards":[{"number":"4111111111111111"},{"number":"[redacted]"}]}"""));

        // Given keys that hold a slash and a tilde
        // When the pointers use the RFC 6901 escapes ~1 and ~0
        // Then the values at those keys are replaced and the key that merely looks similar is not
        assertThat(redact("""
                {"a/b":"secret-1","m~n":"secret-2","a":{"b":"public"}}""", "/a~1b", "/m~0n")).as("escaped keys").isEqualTo(new JsonObject("""
                        {"a/b":"[redacted]","m~n":"[redacted]","a":{"b":"public"}}"""));

        // Given a pointer that names a whole subtree
        // When it is redacted
        // Then the subtree becomes the marker
        assertThat(redact("""
                {"card":{"number":"4111111111111111","holder":"A. Payer"},"ref":"r-1"}""", "/card")).as("a whole subtree").isEqualTo(new JsonObject("""
                        {"card":"[redacted]","ref":"r-1"}"""));

        // Given pointers of which one resolves and one does not
        // When they are redacted together
        // Then the resolvable one is replaced and the missing one is ignored
        assertThat(redact("""
                {"card":{"number":"4111111111111111"}}""", "/card/number", "/payer/iban", "/card/number/last4"))
                .as("a mix of resolvable and missing pointers")
                .isEqualTo(new JsonObject("""
                        {"card":{"number":"[redacted]"}}"""));

        // Given a tree without the named path
        // When the pointer is redacted
        // Then the tree is unchanged
        String untouched = """
                {"card":{"holder":"A. Payer"},"items":[1,2],"ref":"r-1"}""";
        assertThat(redact(untouched, "/card/number", "/items/5", "/nothing/here"))
                .as("missing paths")
                .isEqualTo(new JsonObject(untouched));

        // Given an empty pointer set
        // When it is applied
        // Then the tree is unchanged
        assertThat(redact(untouched)).as("an empty pointer set").isEqualTo(new JsonObject(untouched));

        // Given a pointer that is not a JSON Pointer, and a tree that holds a secret
        // When it is applied
        // Then it fails with an IllegalArgumentException that names the pointer and never a value
        assertThatThrownBy(() -> redact("""
                        {"card":{"number":"4111111111111111"}}""", "card/number"))
                .as("an invalid pointer")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("card/number")
                .hasMessageNotContaining("4111111111111111");
    }
}
