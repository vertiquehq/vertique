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
 * Verifies {@link PayloadMapperRegistry} and its {@link DefaultPayloadMapperRegistry} implementation:
 * registration, lookup, conflict detection, and empty-registry behaviour.
 */
class PayloadMapperRegistryTest {

    // --- Shared test state ---

    /** Minimal state type used as the generic {@code S} parameter. */
    record _State(String value) {}

    private static final Function<_State, Object> MAPPER_A = s -> s.value() + "_payload";
    private static final Function<_State, Object> MAPPER_B = s -> s.value() + "_other";

    private static final NamedPayloadMapper<_State> NAMED_A =
            new NamedPayloadMapper<>("order.payload", _State.class, MAPPER_A);
    private static final NamedPayloadMapper<_State> NAMED_B =
            new NamedPayloadMapper<>("order.reduce", _State.class, MAPPER_B);

    private PayloadMapperRegistry registry(PayloadMapperContributor... contributors) {
        return new DefaultPayloadMapperRegistry(Set.of(contributors));
    }

    // --- Tests ---

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named mapper by id")
        void returnsRegisteredEntry() {
            PayloadMapperRegistry reg = registry(b -> b.register(NAMED_A));
            assertThat(reg.lookup("order.payload")).isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id listing known ids")
        void throwsForUnknownId() {
            PayloadMapperRegistry reg = registry(b -> b.register(NAMED_A));
            assertThatThrownBy(() -> reg.lookup("missing.id"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("missing.id")
                    .hasMessageContaining("order.payload");
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException with registry role in message")
        void messageContainsRole() {
            PayloadMapperRegistry reg = registry();
            assertThatThrownBy(() -> reg.lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("payload-mapper");
        }
    }

    @Nested
    @DisplayName("contains")
    class Contains {

        @Test
        @DisplayName("returns true for a registered id")
        void trueForRegistered() {
            assertThat(registry(b -> b.register(NAMED_A)).contains("order.payload"))
                    .isTrue();
        }

        @Test
        @DisplayName("returns false for an unregistered id")
        void falseForUnregistered() {
            assertThat(registry(b -> b.register(NAMED_A)).contains("other")).isFalse();
        }
    }

    @Nested
    @DisplayName("ids")
    class Ids {

        @Test
        @DisplayName("empty set when no contributors")
        void emptyWhenNoContributors() {
            assertThat(registry().ids()).isEmpty();
        }

        @Test
        @DisplayName("contains all registered ids from multiple contributors")
        void containsAllIds() {
            PayloadMapperRegistry reg = registry(b -> b.register(NAMED_A), b -> b.register(NAMED_B));
            assertThat(reg.ids()).containsExactlyInAnyOrder("order.payload", "order.reduce");
        }
    }

    @Nested
    @DisplayName("conflict detection")
    class ConflictDetection {

        @Test
        @DisplayName("same record registered twice (idempotent) does not throw")
        void sameRecordTwiceIsNoOp() {
            PayloadMapperRegistry reg = registry(b -> b.register(NAMED_A), b -> b.register(NAMED_A));
            assertThat(reg.ids()).containsExactly("order.payload");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException at construction")
        void differentRecordsSameIdThrows() {
            NamedPayloadMapper<_State> conflicting = new NamedPayloadMapper<>("order.payload", _State.class, MAPPER_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.payload");
        }
    }

    @Nested
    @DisplayName("NamedPayloadMapper record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedPayloadMapper<>(null, _State.class, MAPPER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedPayloadMapper<>("  ", _State.class, MAPPER_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedPayloadMapper<_State>("id", null, MAPPER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null mapper throws NullPointerException")
        void nullMapperThrows() {
            assertThatThrownBy(() -> new NamedPayloadMapper<>("id", _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
