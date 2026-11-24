// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifies the {@code (kind, value)} projection of a {@link WorkflowActor} and its way back. */
class WorkflowActorMapsTest {

    private static Stream<Arguments> actors() {
        return Stream.of(
                Arguments.of(new WorkflowActor.User("alice"), "USER", "alice"),
                Arguments.of(new WorkflowActor.Service("payment-svc"), "SERVICE", "payment-svc"),
                Arguments.of(new WorkflowActor.System("workflow-cancelled"), "SYSTEM", "workflow-cancelled"),
                Arguments.of(new WorkflowActor.Anonymous(), "ANONYMOUS", ""));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("actors")
    @DisplayName("an actor projects to its kind and value, and the pair rebuilds the actor")
    void anActorProjectsAndRebuilds(WorkflowActor actor, String kind, String value) {
        // When the actor is projected
        Map<String, String> map = WorkflowActorMaps.toMap(actor);

        // Then it has the kind and the value
        assertThat(map).containsExactlyInAnyOrderEntriesOf(Map.of("kind", kind, "value", value));

        // And the pair rebuilds the actor
        assertThat(WorkflowActorMaps.toActor(map.get("kind"), map.get("value"))).isEqualTo(actor);
    }

    @Test
    @DisplayName("an anonymous actor ignores the value of its pair")
    void anAnonymousActorIgnoresTheValue() {
        assertThat(WorkflowActorMaps.toActor("ANONYMOUS", null)).isEqualTo(new WorkflowActor.Anonymous());
        assertThat(WorkflowActorMaps.toActor("ANONYMOUS", "anything")).isEqualTo(new WorkflowActor.Anonymous());
    }

    @Test
    @DisplayName("an unknown or missing kind is refused, and the message never repeats the value")
    void anUnknownKindIsRefusedWithoutTheValue() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WorkflowActorMaps.toActor("ROBOT", "secret-value"))
                .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain("secret-value"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WorkflowActorMaps.toActor(null, "secret-value"))
                .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain("secret-value"));
    }

    @Test
    @DisplayName("a missing or blank value is refused for a kind that needs one")
    void aMissingValueIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowActorMaps.toActor("USER", null));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowActorMaps.toActor("SERVICE", " "));
    }
}
