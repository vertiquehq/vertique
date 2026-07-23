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
 * Verifies {@link StartStateMapperRegistry} and its {@link DefaultStartStateMapperRegistry} implementation:
 * registration, lookup, conflict detection, and validation of both payloadType and stateType.
 */
class StartStateMapperRegistryTest {

    /** Minimal payload type used as {@code P}. */
    record _Payload(String raw) {}

    /** Minimal state type used as {@code S}. */
    record _State(String value) {}

    private static final Function<_Payload, _State> MAPPER_A = p -> new _State(p.raw() + "_state");
    private static final Function<_Payload, _State> MAPPER_B = p -> new _State(p.raw() + "_other");

    private static final NamedStartStateMapper<_Payload, _State> NAMED_A =
            new NamedStartStateMapper<>("order.start", _Payload.class, _State.class, MAPPER_A);

    private StartStateMapperRegistry registry(StartStateMapperContributor... contributors) {
        return new DefaultStartStateMapperRegistry(Set.of(contributors));
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named mapper by id")
        void returnsRegisteredEntry() {
            StartStateMapperRegistry reg = registry(b -> b.register(NAMED_A));
            assertThat(reg.lookup("order.start")).isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id listing known ids")
        void throwsForUnknownId() {
            StartStateMapperRegistry reg = registry(b -> b.register(NAMED_A));
            assertThatThrownBy(() -> reg.lookup("missing"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("missing")
                    .hasMessageContaining("order.start");
        }

        @Test
        @DisplayName("throws when empty registry, mentions role in message")
        void throwsWithRoleInMessage() {
            assertThatThrownBy(() -> registry().lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("start-state-mapper");
        }
    }

    @Nested
    @DisplayName("contains and ids")
    class ContainsAndIds {

        @Test
        @DisplayName("contains returns true for registered id")
        void containsTrue() {
            assertThat(registry(b -> b.register(NAMED_A)).contains("order.start"))
                    .isTrue();
        }

        @Test
        @DisplayName("ids is empty when no contributors")
        void idsEmptyWhenNoContributors() {
            assertThat(registry().ids()).isEmpty();
        }
    }

    @Nested
    @DisplayName("conflict detection")
    class ConflictDetection {

        @Test
        @DisplayName("same record twice is idempotent")
        void sameRecordTwiceIsNoOp() {
            StartStateMapperRegistry reg = registry(b -> b.register(NAMED_A), b -> b.register(NAMED_A));
            assertThat(reg.ids()).containsExactly("order.start");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException")
        void conflictThrows() {
            NamedStartStateMapper<_Payload, _State> conflicting =
                    new NamedStartStateMapper<>("order.start", _Payload.class, _State.class, MAPPER_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.start");
        }
    }

    @Nested
    @DisplayName("NamedStartStateMapper record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedStartStateMapper<>(null, _Payload.class, _State.class, MAPPER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedStartStateMapper<>("  ", _Payload.class, _State.class, MAPPER_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null payloadType throws NullPointerException")
        void nullPayloadTypeThrows() {
            assertThatThrownBy(() -> new NamedStartStateMapper<_Payload, _State>("id", null, _State.class, MAPPER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedStartStateMapper<_Payload, _State>("id", _Payload.class, null, MAPPER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null mapper throws NullPointerException")
        void nullMapperThrows() {
            assertThatThrownBy(() -> new NamedStartStateMapper<>("id", _Payload.class, _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
