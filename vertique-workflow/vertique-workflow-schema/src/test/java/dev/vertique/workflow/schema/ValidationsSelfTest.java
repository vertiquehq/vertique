// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.validation.ViolationDetail;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves the value-leak assertion can fail, so that a pass in the validation tests means something. */
class ValidationsSelfTest {

    private static final String SENTINEL = "ZZSENTINEL";

    private static PayloadValidationResult resultOf(ViolationDetail detail) {
        return new PayloadValidationResult(List.of(detail));
    }

    @Test
    @DisplayName("a result without the fragment passes, and so does a valid result and a detail without type or args")
    void aResultWithoutTheFragmentPasses() {
        // Given: results that do not contain the sentinel
        PayloadValidationResult clean =
                resultOf(new ViolationDetail("/comment", "must be of type: string", "type", Map.of("type", "string")));
        PayloadValidationResult valid = new PayloadValidationResult(List.of());
        PayloadValidationResult bare = resultOf(ViolationDetail.of("", "does not satisfy the schema"));

        // When / Then: the assertion accepts all of them
        assertThatCode(() -> Validations.assertNoValueIn(clean, SENTINEL)).doesNotThrowAnyException();
        assertThatCode(() -> Validations.assertNoValueIn(valid, SENTINEL)).doesNotThrowAnyException();
        assertThatCode(() -> Validations.assertNoValueIn(bare, SENTINEL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a fragment in the path is found")
    void aFragmentInThePathIsFound() {
        // Given: a detail whose path carries the sentinel
        PayloadValidationResult leaking = resultOf(new ViolationDetail("/" + SENTINEL, "invalid", "type", null));

        // When / Then: the assertion fails
        assertThatThrownBy(() -> Validations.assertNoValueIn(leaking, SENTINEL)).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("a fragment in the message is found")
    void aFragmentInTheMessageIsFound() {
        PayloadValidationResult leaking = resultOf(new ViolationDetail("/a", "got " + SENTINEL, "type", null));

        assertThatThrownBy(() -> Validations.assertNoValueIn(leaking, SENTINEL)).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("a fragment in the type is found")
    void aFragmentInTheTypeIsFound() {
        PayloadValidationResult leaking = resultOf(new ViolationDetail("/a", "invalid", SENTINEL, null));

        assertThatThrownBy(() -> Validations.assertNoValueIn(leaking, SENTINEL)).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("a fragment in an argument name is found")
    void aFragmentInAnArgumentNameIsFound() {
        PayloadValidationResult leaking = resultOf(new ViolationDetail("/a", "invalid", "type", Map.of(SENTINEL, 1)));

        assertThatThrownBy(() -> Validations.assertNoValueIn(leaking, SENTINEL)).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("a fragment in an argument value is found")
    void aFragmentInAnArgumentValueIsFound() {
        PayloadValidationResult leaking =
                resultOf(new ViolationDetail("/a", "invalid", "type", Map.of("limit", "x" + SENTINEL)));

        assertThatThrownBy(() -> Validations.assertNoValueIn(leaking, SENTINEL)).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("any one of several fragments is found")
    void anyOneOfSeveralFragmentsIsFound() {
        PayloadValidationResult leaking = resultOf(new ViolationDetail("/a", "invalid " + SENTINEL, "type", null));

        assertThatThrownBy(() -> Validations.assertNoValueIn(leaking, "other", SENTINEL))
                .isInstanceOf(AssertionError.class);
    }
}
