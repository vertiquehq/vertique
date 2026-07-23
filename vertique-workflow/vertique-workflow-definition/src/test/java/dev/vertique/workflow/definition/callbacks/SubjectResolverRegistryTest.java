// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link SubjectResolverRegistry} and its {@link DefaultSubjectResolverRegistry} implementation.
 */
class SubjectResolverRegistryTest {

    record _State(String value) {}

    private static final Function<_State, WorkflowSubjectRef> RESOLVER_A =
            s -> new WorkflowSubjectRef("Order", s.value(), null);
    private static final Function<_State, WorkflowSubjectRef> RESOLVER_B =
            s -> new WorkflowSubjectRef("Customer", s.value(), null);

    private static final NamedSubjectResolver<_State> NAMED_A =
            new NamedSubjectResolver<>("order.subject", _State.class, RESOLVER_A);

    private SubjectResolverRegistry registry(SubjectResolverContributor... contributors) {
        return new DefaultSubjectResolverRegistry(Set.of(contributors));
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named resolver by id")
        void returnsRegisteredEntry() {
            assertThat(registry(b -> b.register(NAMED_A)).lookup("order.subject"))
                    .isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id")
        void throwsForUnknownId() {
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A)).lookup("missing"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("missing")
                    .hasMessageContaining("order.subject");
        }

        @Test
        @DisplayName("message contains registry role")
        void messageContainsRole() {
            assertThatThrownBy(() -> registry().lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("subject-resolver");
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
            assertThat(registry(b -> b.register(NAMED_A)).contains("order.subject"))
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
                    .containsExactly("order.subject");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException")
        void conflictThrows() {
            NamedSubjectResolver<_State> conflicting =
                    new NamedSubjectResolver<>("order.subject", _State.class, RESOLVER_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.subject");
        }
    }

    @Nested
    @DisplayName("NamedSubjectResolver record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedSubjectResolver<>(null, _State.class, RESOLVER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedSubjectResolver<>("  ", _State.class, RESOLVER_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedSubjectResolver<_State>("id", null, RESOLVER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null resolver throws NullPointerException")
        void nullResolverThrows() {
            assertThatThrownBy(() -> new NamedSubjectResolver<>("id", _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
