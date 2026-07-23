// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link MigrationResult}: factory methods, sealed-type discrimination, null/blank
 * validation, and exhaustive switch coverage.
 */
class MigrationResultTest {

    record SomeState(String value) {}

    // --- Factory methods ---

    @Nested
    @DisplayName("anchorAtInitial factory")
    class AnchorAtInitialFactory {

        @Test
        @DisplayName("returns AnchorAtInitial with the supplied state")
        void returnsCorrectType() {
            SomeState state = new SomeState("s1");
            MigrationResult<SomeState> result = MigrationResult.anchorAtInitial(state);
            assertThat(result).isInstanceOf(MigrationResult.AnchorAtInitial.class);
            assertThat(result.newState()).isEqualTo(state);
        }

        @Test
        @DisplayName("rejects null newState")
        void rejectsNullState() {
            assertThatThrownBy(() -> MigrationResult.anchorAtInitial(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("continueAt factory")
    class ContinueAtFactory {

        @Test
        @DisplayName("returns ContinueAt with the supplied state and stepId")
        void returnsCorrectType() {
            SomeState state = new SomeState("s2");
            MigrationResult<SomeState> result = MigrationResult.continueAt(state, "step-two");
            assertThat(result).isInstanceOf(MigrationResult.ContinueAt.class);
            assertThat(result.newState()).isEqualTo(state);
            assertThat(((MigrationResult.ContinueAt<SomeState>) result).stepId())
                    .isEqualTo("step-two");
        }

        @Test
        @DisplayName("rejects null newState")
        void rejectsNullState() {
            assertThatThrownBy(() -> MigrationResult.continueAt(null, "step-x"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null stepId")
        void rejectsNullStepId() {
            SomeState state = new SomeState("s");
            assertThatThrownBy(() -> MigrationResult.continueAt(state, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects blank stepId")
        void rejectsBlankStepId() {
            SomeState state = new SomeState("s");
            assertThatThrownBy(() -> MigrationResult.continueAt(state, "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stepId");
        }

        @Test
        @DisplayName("rejects empty stepId")
        void rejectsEmptyStepId() {
            SomeState state = new SomeState("s");
            assertThatThrownBy(() -> MigrationResult.continueAt(state, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stepId");
        }
    }

    // --- Compact-constructor validation ---

    @Nested
    @DisplayName("AnchorAtInitial compact constructor")
    class AnchorAtInitialConstructor {

        @Test
        @DisplayName("direct construction rejects null newState")
        void rejectsNullNewState() {
            assertThatThrownBy(() -> new MigrationResult.AnchorAtInitial<>(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("ContinueAt compact constructor")
    class ContinueAtConstructor {

        @Test
        @DisplayName("direct construction rejects null newState")
        void rejectsNullNewState() {
            assertThatThrownBy(() -> new MigrationResult.ContinueAt<>(null, "step"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("direct construction rejects null stepId")
        void rejectsNullStepId() {
            assertThatThrownBy(() -> new MigrationResult.ContinueAt<>(new SomeState("x"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("direct construction rejects blank stepId")
        void rejectsBlankStepId() {
            assertThatThrownBy(() -> new MigrationResult.ContinueAt<>(new SomeState("x"), "  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- Sealed exhaustive switch ---

    @Nested
    @DisplayName("sealed exhaustive switch")
    class ExhaustiveSwitch {

        @Test
        @DisplayName("switch covers AnchorAtInitial and ContinueAt without default")
        void switchIsExhaustive() {
            MigrationResult<SomeState> anchor = MigrationResult.anchorAtInitial(new SomeState("a"));
            MigrationResult<SomeState> cont = MigrationResult.continueAt(new SomeState("b"), "next");

            String anchorLabel = classify(anchor);
            String contLabel = classify(cont);

            assertThat(anchorLabel).isEqualTo("anchor");
            assertThat(contLabel).isEqualTo("continue");
        }

        /** Helper that exercises a sealed switch without a default arm. */
        private static String classify(MigrationResult<?> result) {
            return switch (result) {
                case MigrationResult.AnchorAtInitial<?> ignored -> "anchor";
                case MigrationResult.ContinueAt<?> ignored -> "continue";
            };
        }
    }
}
