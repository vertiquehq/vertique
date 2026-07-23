// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.sideeffect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the factory methods, structural variants, and pattern-matching exhaustiveness of
 * {@link RecorderResult}.
 */
class RecorderResultTest {

    @Nested
    @DisplayName("empty()")
    class EmptyVariant {

        @Test
        @DisplayName("returns the Empty singleton")
        void returnsEmptySingleton() {
            RecorderResult result = RecorderResult.empty();
            assertInstanceOf(RecorderResult.Empty.class, result);
        }

        @Test
        @DisplayName("always returns the same EMPTY constant reference")
        void returnsSameConstantReference() {
            assertSame(RecorderResult.EMPTY, RecorderResult.empty());
            assertSame(RecorderResult.empty(), RecorderResult.empty());
        }
    }

    @Nested
    @DisplayName("ofTimer(UUID)")
    class TimerVariant {

        @Test
        @DisplayName("returns Timer instance wrapping the given UUID")
        void returnsTimerWithUuid() {
            UUID id = UUID.randomUUID();
            RecorderResult result = RecorderResult.ofTimer(id);
            assertInstanceOf(RecorderResult.Timer.class, result);
            assertThat(((RecorderResult.Timer) result).timerId()).isEqualTo(id);
        }

        @Test
        @DisplayName("each call produces a distinct Timer instance")
        void distinctInstancesPerCall() {
            UUID id = UUID.randomUUID();
            RecorderResult a = RecorderResult.ofTimer(id);
            RecorderResult b = RecorderResult.ofTimer(id);
            assertThat(a).isEqualTo(b); // record equality
            assertThat(a).isNotSameAs(b); // distinct objects
        }
    }

    @Nested
    @DisplayName("pattern matching")
    class PatternMatching {

        @Test
        @DisplayName("switch over Empty variant produces expected label")
        void switchOverEmpty() {
            RecorderResult result = RecorderResult.empty();
            String label =
                    switch (result) {
                        case RecorderResult.Empty ignored -> "empty";
                        case RecorderResult.Timer t -> "timer:" + t.timerId();
                    };
            assertThat(label).isEqualTo("empty");
        }

        @Test
        @DisplayName("switch over Timer variant produces expected label")
        void switchOverTimer() {
            UUID id = UUID.randomUUID();
            RecorderResult result = RecorderResult.ofTimer(id);
            String label =
                    switch (result) {
                        case RecorderResult.Empty ignored -> "empty";
                        case RecorderResult.Timer t -> "timer:" + t.timerId();
                    };
            assertThat(label).isEqualTo("timer:" + id);
        }
    }
}
