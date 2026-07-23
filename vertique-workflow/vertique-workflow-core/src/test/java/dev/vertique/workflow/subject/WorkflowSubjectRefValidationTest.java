// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the compact-constructor validation of {@link WorkflowSubjectRef}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Valid construction (with and without version).</li>
 *   <li>Null {@code type} → {@link NullPointerException}.</li>
 *   <li>Blank {@code type} (empty string and whitespace) → {@link IllegalArgumentException}.</li>
 *   <li>Null {@code id} → {@link NullPointerException}.</li>
 *   <li>Blank {@code id} (empty string and whitespace) → {@link IllegalArgumentException}.</li>
 *   <li>Non-null blank {@code version} → {@link IllegalArgumentException}.</li>
 *   <li>Null {@code version} accepted (optional).</li>
 * </ul>
 */
class WorkflowSubjectRefValidationTest {

    // --- (a) Valid construction ---

    @Nested
    @DisplayName("(a) valid construction")
    class ValidConstruction {

        @Test
        @DisplayName("valid ref with non-null version is accepted")
        void validRefWithVersion() {
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "123", "v3");
            assertThat(ref.type()).isEqualTo("Article");
            assertThat(ref.id()).isEqualTo("123");
            assertThat(ref.version()).isEqualTo("v3");
        }

        @Test
        @DisplayName("valid ref with null version is accepted")
        void validRefWithNullVersion() {
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Order", "order-42", null);
            assertThat(ref.type()).isEqualTo("Order");
            assertThat(ref.id()).isEqualTo("order-42");
            assertThat(ref.version()).isNull();
        }
    }

    // --- (b) type validation ---

    @Nested
    @DisplayName("(b) type validation")
    class TypeValidation {

        @Test
        @DisplayName("null type throws NullPointerException")
        void nullTypeThrows() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorkflowSubjectRef(null, "123", null))
                    .withMessage("type");
        }

        @Test
        @DisplayName("empty type throws IllegalArgumentException")
        void emptyTypeThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WorkflowSubjectRef("", "123", null))
                    .withMessage("type must not be blank");
        }

        @Test
        @DisplayName("whitespace-only type throws IllegalArgumentException")
        void whitespaceTypeThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WorkflowSubjectRef("   ", "123", null))
                    .withMessage("type must not be blank");
        }
    }

    // --- (c) id validation ---

    @Nested
    @DisplayName("(c) id validation")
    class IdValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorkflowSubjectRef("Article", null, null))
                    .withMessage("id");
        }

        @Test
        @DisplayName("empty id throws IllegalArgumentException")
        void emptyIdThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WorkflowSubjectRef("Article", "", null))
                    .withMessage("id must not be blank");
        }

        @Test
        @DisplayName("whitespace-only id throws IllegalArgumentException")
        void whitespaceIdThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WorkflowSubjectRef("Article", "   ", null))
                    .withMessage("id must not be blank");
        }
    }

    // --- (d) version validation ---

    @Nested
    @DisplayName("(d) version validation")
    class VersionValidation {

        @Test
        @DisplayName("null version is accepted (optional field)")
        void nullVersionAccepted() {
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "123", null);
            assertThat(ref.version()).isNull();
        }

        @Test
        @DisplayName("non-null blank version (empty string) throws IllegalArgumentException")
        void emptyVersionThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WorkflowSubjectRef("Article", "123", ""))
                    .withMessage("version must not be blank");
        }

        @Test
        @DisplayName("non-null blank version (whitespace) throws IllegalArgumentException")
        void whitespaceVersionThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WorkflowSubjectRef("Article", "123", "   "))
                    .withMessage("version must not be blank");
        }
    }
}
