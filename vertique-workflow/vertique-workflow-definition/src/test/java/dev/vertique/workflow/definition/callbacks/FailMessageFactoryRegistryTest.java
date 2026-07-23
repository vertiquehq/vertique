// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link FailMessageFactoryRegistry} and its {@link DefaultFailMessageFactoryRegistry} implementation.
 */
class FailMessageFactoryRegistryTest {

    record _State(String value) {}

    private static final Function<_State, String> FACTORY_A = s -> "error: " + s.value();
    private static final Function<_State, String> FACTORY_B = s -> "fail: " + s.value();

    private static final NamedFailMessageFactory<_State> NAMED_A =
            new NamedFailMessageFactory<>("order.fail.msg", _State.class, FACTORY_A);

    private FailMessageFactoryRegistry registry(FailMessageFactoryContributor... contributors) {
        return new DefaultFailMessageFactoryRegistry(Set.of(contributors));
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named factory by id")
        void returnsRegisteredEntry() {
            assertThat(registry(b -> b.register(NAMED_A)).lookup("order.fail.msg"))
                    .isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id")
        void throwsForUnknownId() {
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A)).lookup("missing"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("missing")
                    .hasMessageContaining("order.fail.msg");
        }

        @Test
        @DisplayName("message contains registry role")
        void messageContainsRole() {
            assertThatThrownBy(() -> registry().lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("fail-message-factory");
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
        @DisplayName("contains returns true for registered id")
        void containsTrue() {
            assertThat(registry(b -> b.register(NAMED_A)).contains("order.fail.msg"))
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
                    .containsExactly("order.fail.msg");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException")
        void conflictThrows() {
            NamedFailMessageFactory<_State> conflicting =
                    new NamedFailMessageFactory<>("order.fail.msg", _State.class, FACTORY_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.fail.msg");
        }
    }

    @Nested
    @DisplayName("NamedFailMessageFactory record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedFailMessageFactory<>(null, _State.class, FACTORY_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedFailMessageFactory<>("  ", _State.class, FACTORY_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedFailMessageFactory<_State>("id", null, FACTORY_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null factory throws NullPointerException")
        void nullFactoryThrows() {
            assertThatThrownBy(() -> new NamedFailMessageFactory<>("id", _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
