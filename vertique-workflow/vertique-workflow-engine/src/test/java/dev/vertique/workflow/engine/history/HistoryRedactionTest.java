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
        return new JsonObject(HistoryRedaction.apply(ExactJson.readObject(tree), Set.of(pointers))
                .toString());
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

    @Test
    @DisplayName("a wildcard segment matches every element of an array and every member of an object")
    void wildcardMatchesEveryElementAndMember() {
        // Given three cards in an array
        String cards = """
                {"cards":[{"number":"1","brand":"a"},{"number":"2","brand":"b"},{"number":"3","brand":"c"}]}""";

        // When the number of every card is named by a wildcard
        // Then every number is replaced and nothing else
        assertThat(redact(cards, "/cards/*/number")).isEqualTo(new JsonObject("""
                        {"cards":[{"number":"[redacted]","brand":"a"},{"number":"[redacted]","brand":"b"},\
                        {"number":"[redacted]","brand":"c"}]}"""));

        // Given an object keyed by an id
        // When the wildcard stands for the key
        // Then every member is replaced
        assertThat(redact("""
                {"by":{"x":{"pan":"1"},"y":{"pan":"2"}}}""", "/by/*/pan")).isEqualTo(new JsonObject("""
                        {"by":{"x":{"pan":"[redacted]"},"y":{"pan":"[redacted]"}}}"""));

        // Given nested arrays
        // When two wildcards are used
        // Then every inner value is replaced
        assertThat(redact("""
                {"a":[{"b":[{"s":1},{"s":2}]},{"b":[{"s":3}]}]}""", "/a/*/b/*/s")).isEqualTo(new JsonObject("""
                        {"a":[{"b":[{"s":"[redacted]"},{"s":"[redacted]"}]},{"b":[{"s":"[redacted]"}]}]}"""));

        // Given a pointer that ends in the wildcard
        // When it is applied
        // Then every non-null element is replaced and a null element stays
        assertThat(redact("""
                {"keys":["k1",null,"k3"]}""", "/keys/*")).isEqualTo(new JsonObject("""
                        {"keys":["[redacted]",null,"[redacted]"]}"""));

        // Given a wildcard where the document holds a scalar or nothing
        // When it is applied
        // Then the document is unchanged
        String untouched = """
                {"cards":"none","other":1}""";
        assertThat(redact(untouched, "/cards/*/number", "/missing/*")).isEqualTo(new JsonObject(untouched));
    }

    @Test
    @DisplayName("a wildcard redacts the same elements after an array shifts, which an index would not")
    void wildcardSurvivesArrayShifts() {
        // Given a card number that sat at index 1 and moved to index 0 after the first card was removed
        String before = """
                {"cards":[{"number":"1"},{"number":"2"}]}""";
        String after = """
                {"cards":[{"number":"2"}]}""";

        // When an index pointer and a wildcard are applied to both
        // Then the index pointer leaks the shifted number and the wildcard does not
        assertThat(redact(before, "/cards/1/number")).isNotEqualTo(redact(before, "/cards/*/number"));
        assertThat(redact(after, "/cards/1/number")).isEqualTo(new JsonObject(after));
        assertThat(redact(after, "/cards/*/number")).isEqualTo(new JsonObject("""
                        {"cards":[{"number":"[redacted]"}]}"""));
    }

    @Test
    @DisplayName("the values a wildcard reaches are the ones removed from a text")
    void wildcardValuesAreRemovedFromText() {
        // Given a document with two secrets and a reason that echoes both
        var values = HistoryRedaction.valuesAt(
                ExactJson.read("{\"cards\":[{\"n\":\"AAA\"},{\"n\":\"BBB\"}]}"), Set.of("/cards/*/n"));

        // When the text is redacted
        // Then both are replaced
        assertThat(HistoryRedaction.redactText("bad AAA and BBB", values)).isEqualTo("bad [redacted] and [redacted]");
    }

    @Test
    @DisplayName("an invalid escape is refused with the pointer and no value")
    void invalidEscapeIsRefused() {
        assertThatThrownBy(() -> redact("""
                        {"card":"4111111111111111"}""", "/card~2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/card~2")
                .hasMessageNotContaining("4111111111111111");
    }
}
