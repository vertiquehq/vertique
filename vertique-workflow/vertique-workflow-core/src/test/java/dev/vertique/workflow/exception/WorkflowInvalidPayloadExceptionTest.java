// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vertique.core.exception.BusinessRuleException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.core.validation.ViolationCarrier;
import dev.vertique.core.validation.ViolationDetail;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowInvalidPayloadException}: its place in the exception hierarchy, the
 * {@link ViolationCarrier} contract, and the defensive copy of the violations in both constructors.
 */
class WorkflowInvalidPayloadExceptionTest {

    private static final ViolationDetail DETAIL = ViolationDetail.of("/approved", "must not be null");

    @Test
    @DisplayName("is a ValidationException carrying unmodifiable violations and a cause")
    void isAValidationExceptionCarryingUnmodifiableViolationsAndCause() {
        // Given: both constructor forms
        IllegalArgumentException cause = new IllegalArgumentException("binding failed");
        WorkflowInvalidPayloadException withCause =
                new WorkflowInvalidPayloadException("invalid decision payload", List.of(DETAIL), cause);
        WorkflowInvalidPayloadException withoutCause =
                new WorkflowInvalidPayloadException("invalid decision payload", List.of(DETAIL));

        // When / Then: both sit in the 400-class hierarchy and are violation carriers
        for (WorkflowInvalidPayloadException exception : List.of(withCause, withoutCause)) {
            assertInstanceOf(WorkflowException.class, exception);
            assertInstanceOf(BusinessRuleException.class, exception);
            assertInstanceOf(ValidationException.class, exception);
            assertInstanceOf(ViolationCarrier.class, exception);
            assertThat(exception.violations()).isEqualTo(List.of(DETAIL));
            assertThatThrownBy(() -> exception.violations().add(DETAIL))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(withCause.getCause()).isSameAs(cause);
        assertThat(withoutCause.getCause()).isNull();
    }

    @Nested
    @DisplayName("message, violations and cause constructor")
    class WithCause {

        private final IllegalStateException cause = new IllegalStateException("underlying");

        @Test
        @DisplayName("keeps the message, the violations and the cause")
        void keepsMessageViolationsAndCause() {
            WorkflowInvalidPayloadException exception =
                    new WorkflowInvalidPayloadException("invalid decision payload", List.of(DETAIL), cause);

            assertThat(exception.getMessage()).isEqualTo("invalid decision payload");
            assertThat(exception.violations()).containsExactly(DETAIL);
            assertThat(exception.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("exposes an unmodifiable list")
        void violationsAreUnmodifiable() {
            WorkflowInvalidPayloadException exception =
                    new WorkflowInvalidPayloadException("invalid", List.of(DETAIL), cause);

            assertThatThrownBy(() -> exception.violations().add(DETAIL))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> exception.violations().clear()).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("holds a copy: mutating the source list afterwards does not change it")
        void violationsAreACopy() {
            List<ViolationDetail> source = new ArrayList<>(List.of(DETAIL));
            WorkflowInvalidPayloadException exception = new WorkflowInvalidPayloadException("invalid", source, cause);

            source.add(ViolationDetail.of("/other", "later"));
            source.clear();

            assertThat(exception.violations()).containsExactly(DETAIL);
        }

        @Test
        @DisplayName("an empty list is carried as an empty list")
        void emptyViolationsAreCarried() {
            WorkflowInvalidPayloadException exception =
                    new WorkflowInvalidPayloadException("invalid", List.of(), cause);

            assertThat(exception.violations()).isEmpty();
        }

        @Test
        @DisplayName("null violations fail fast")
        void nullViolationsFailFast() {
            assertThatThrownBy(() -> new WorkflowInvalidPayloadException("invalid", null, cause))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("message and violations constructor")
    class WithoutCause {

        @Test
        @DisplayName("keeps the message and the violations and has no cause")
        void keepsMessageAndViolationsWithNullCause() {
            WorkflowInvalidPayloadException exception =
                    new WorkflowInvalidPayloadException("invalid decision payload", List.of(DETAIL));

            assertThat(exception.getMessage()).isEqualTo("invalid decision payload");
            assertThat(exception.violations()).containsExactly(DETAIL);
            assertThat(exception.getCause()).isNull();
        }

        @Test
        @DisplayName("exposes an unmodifiable list")
        void violationsAreUnmodifiable() {
            WorkflowInvalidPayloadException exception = new WorkflowInvalidPayloadException("invalid", List.of(DETAIL));

            assertThatThrownBy(() -> exception.violations().add(DETAIL))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("holds a copy: mutating the source list afterwards does not change it")
        void violationsAreACopy() {
            List<ViolationDetail> source = new ArrayList<>(List.of(DETAIL));
            WorkflowInvalidPayloadException exception = new WorkflowInvalidPayloadException("invalid", source);

            source.add(ViolationDetail.of("/other", "later"));

            assertThat(exception.violations()).containsExactly(DETAIL);
        }

        @Test
        @DisplayName("null violations fail fast")
        void nullViolationsFailFast() {
            assertThatThrownBy(() -> new WorkflowInvalidPayloadException("invalid", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
