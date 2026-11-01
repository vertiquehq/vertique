// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.engine.intake.IntakeAdmissionState.Transition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the admission state of a node: it starts admitting, refuses from a reading at or above the high mark, admits
 * again only from a reading at or below the low mark, and opens intake when reads keep failing.
 */
class IntakeAdmissionStateTest {

    private static final int HIGH = 100;
    private static final int LOW = 40;

    private final IntakeAdmissionState state = new IntakeAdmissionState(HIGH, LOW);

    @Nested
    @DisplayName("the marks")
    class Marks {

        @Test
        @DisplayName("a new node admits")
        void startsAdmitting() {
            assertThat(state.admitting()).isTrue();
            assertThat(state.lastDue()).isZero();
        }

        @Test
        @DisplayName("refuses from a reading at the high mark, not from one just below it")
        void refusesAtTheHighMark() {
            assertThat(state.onReading(HIGH - 1)).isNull();
            assertThat(state.admitting()).isTrue();

            Transition refused = state.onReading(HIGH);

            assertThat(refused).isEqualTo(new Transition(true, HIGH, false));
            assertThat(state.admitting()).isFalse();
        }

        @Test
        @DisplayName("keeps refusing between the marks after the reading fell from above the high mark")
        void keepsRefusingBetweenTheMarks() {
            state.onReading(HIGH + 1);

            assertThat(state.onReading(HIGH - 1)).isNull();
            assertThat(state.onReading(LOW + 1)).isNull();
            assertThat(state.admitting()).isFalse();
        }

        @Test
        @DisplayName("admits again from a reading at the low mark, not from one just above it")
        void admitsAgainAtTheLowMark() {
            state.onReading(HIGH);
            assertThat(state.onReading(LOW + 1)).isNull();

            Transition admitted = state.onReading(LOW);

            assertThat(admitted).isEqualTo(new Transition(false, LOW, false));
            assertThat(state.admitting()).isTrue();
        }

        @Test
        @DisplayName("keeps admitting between the marks when the reading rose from below the low mark")
        void keepsAdmittingBetweenTheMarks() {
            state.onReading(LOW - 1);

            assertThat(state.onReading(HIGH - 1)).isNull();
            assertThat(state.admitting()).isTrue();
        }

        @Test
        @DisplayName("reports the last good reading capped one above the high mark")
        void capsTheReportedReading() {
            Transition refused = state.onReading(HIGH + 1_000);

            assertThat(refused.due()).isEqualTo(HIGH + 1);
            assertThat(state.lastDue()).isEqualTo(HIGH + 1);
        }

        @Test
        @DisplayName("rejects a low mark that is not below the high mark")
        void rejectsInvertedMarks() {
            assertThatThrownBy(() -> new IntakeAdmissionState(10, 10)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new IntakeAdmissionState(10, 11)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("failed samples")
    class FailedSamples {

        @Test
        @DisplayName("a refusing node keeps refusing through two failed samples and admits on the third")
        void admitsOnTheThirdFailedSample() {
            state.onReading(HIGH + 5);

            assertThat(state.onReadFailed()).isNull();
            assertThat(state.onReadFailed()).isNull();
            assertThat(state.admitting()).isFalse();
            Transition opened = state.onReadFailed();

            assertThat(opened).isEqualTo(new Transition(false, HIGH + 1, true));
            assertThat(state.admitting()).isTrue();
        }

        @Test
        @DisplayName("a good reading between two failed samples restarts the count")
        void aGoodReadingRestartsTheCount() {
            state.onReading(HIGH);
            state.onReadFailed();
            state.onReadFailed();
            state.onReading(HIGH - 1);

            assertThat(state.onReadFailed()).isNull();
            assertThat(state.onReadFailed()).isNull();
            assertThat(state.admitting()).isFalse();
        }

        @Test
        @DisplayName("the next good reading puts the node back under the ordinary rules")
        void aGoodReadingRestoresTheRules() {
            state.onReading(HIGH);
            state.onReadFailed();
            state.onReadFailed();
            state.onReadFailed();
            assertThat(state.admitting()).isTrue();

            assertThat(state.onReading(LOW + 1)).isNull();
            assertThat(state.admitting()).isTrue();
            assertThat(state.onReading(HIGH)).isEqualTo(new Transition(true, HIGH, false));
        }

        @Test
        @DisplayName("an admitting node reports no transition from failed samples, and one outage once")
        void anAdmittingNodeReportsTheOutageOnce() {
            assertThat(state.onReadFailed()).isNull();
            assertThat(state.outageReached()).isFalse();
            assertThat(state.onReadFailed()).isNull();
            assertThat(state.outageReached()).isFalse();
            assertThat(state.onReadFailed()).isNull();
            assertThat(state.outageReached()).isTrue();
            assertThat(state.onReadFailed()).isNull();
            assertThat(state.outageReached()).as("the outage is reported once").isFalse();
            assertThat(state.admitting()).isTrue();
        }
    }
}
