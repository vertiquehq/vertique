// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.time.Instant;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TimerResolverRegistry} and its {@link DefaultTimerResolverRegistry} implementation.
 */
class TimerResolverRegistryTest {

    record _State(String value) {}

    private static final Function<_State, Instant> RESOLVER_A = s -> Instant.EPOCH;
    private static final Function<_State, Instant> RESOLVER_B = s -> Instant.MAX;

    private static final NamedTimerResolver<_State> NAMED_A =
            new NamedTimerResolver<>("order.timer", _State.class, RESOLVER_A);

    private TimerResolverRegistry registry(TimerResolverContributor... contributors) {
        return new DefaultTimerResolverRegistry(Set.of(contributors));
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named resolver by id")
        void returnsRegisteredEntry() {
            assertThat(registry(b -> b.register(NAMED_A)).lookup("order.timer")).isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id")
        void throwsForUnknownId() {
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A)).lookup("unknown"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("unknown")
                    .hasMessageContaining("order.timer");
        }

        @Test
        @DisplayName("message contains registry role")
        void messageContainsRole() {
            assertThatThrownBy(() -> registry().lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("timer-resolver");
        }
    }

    @Nested
    @DisplayName("contains and ids")
    class ContainsAndIds {

        @Test
        @DisplayName("ids is empty when no contributors")
        void idsEmpty() {
            assertThat(registry().ids()).isEmpty();
        }

        @Test
        @DisplayName("contains returns true for registered")
        void containsTrue() {
            assertThat(registry(b -> b.register(NAMED_A)).contains("order.timer"))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("conflict detection")
    class ConflictDetection {

        @Test
        @DisplayName("same record twice is idempotent")
        void sameRecordTwiceIsNoOp() {
            assertThat(registry(b -> b.register(NAMED_A), b -> b.register(NAMED_A))
                            .ids())
                    .containsExactly("order.timer");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException")
        void conflictThrows() {
            NamedTimerResolver<_State> conflicting = new NamedTimerResolver<>("order.timer", _State.class, RESOLVER_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.timer");
        }
    }

    @Nested
    @DisplayName("NamedTimerResolver record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedTimerResolver<>(null, _State.class, RESOLVER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedTimerResolver<>("  ", _State.class, RESOLVER_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedTimerResolver<_State>("id", null, RESOLVER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null resolver throws NullPointerException")
        void nullResolverThrows() {
            assertThatThrownBy(() -> new NamedTimerResolver<>("id", _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
