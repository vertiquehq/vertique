// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.state.HistoryActor;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the actor the registry records carry: who made the last change of a definition row or an activation record,
 * as a trailing component that the constructors that predate it leave unset.
 */
class DefinitionChangeActorRecordsTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final DefinitionChangeActor ALICE = new DefinitionChangeActor(
            new HistoryActor(HistoryActor.Kind.USER, "alice", null), "carol", HistoryAttribution.VERIFIED);

    private static WorkflowDefinitionRecord record(DefinitionChangeActor changedBy) {
        return new WorkflowDefinitionRecord(
                new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1),
                DefinitionKind.CODE,
                "example.Contract",
                "example.State",
                "a".repeat(64),
                DefinitionStatus.AVAILABLE,
                new DefinitionProvenance("code", null, null, null, null, NOW),
                null,
                NOW,
                NOW,
                Map.of(),
                null,
                null,
                null,
                changedBy);
    }

    @Test
    @DisplayName(
            "a definition record carries who made its last status change, and the older constructors leave it unset")
    void aDefinitionRecordCarriesItsLastStatusChange() {
        WorkflowDefinitionRecord attributed = record(ALICE);
        WorkflowDefinitionRecord shortForm = new WorkflowDefinitionRecord(
                attributed.key(),
                attributed.kind(),
                attributed.contractName(),
                attributed.stateTypeName(),
                attributed.planHash(),
                attributed.status(),
                attributed.provenance(),
                attributed.documentFormat(),
                attributed.createdAt(),
                attributed.statusChangedAt(),
                attributed.nameBinding(),
                null);
        WorkflowDefinitionRecord withSchemas = new WorkflowDefinitionRecord(
                attributed.key(),
                attributed.kind(),
                attributed.contractName(),
                attributed.stateTypeName(),
                attributed.planHash(),
                attributed.status(),
                attributed.provenance(),
                attributed.documentFormat(),
                attributed.createdAt(),
                attributed.statusChangedAt(),
                attributed.nameBinding(),
                null,
                null,
                null);

        assertThat(attributed.statusChangedBy()).isEqualTo(ALICE);
        assertThat(shortForm.statusChangedBy()).isNull();
        assertThat(withSchemas.statusChangedBy()).isNull();
        assertThat(record(null).statusChangedBy()).isNull();
    }

    @Test
    @DisplayName("an activation record carries who wrote it, and the five-argument constructor leaves it unset")
    void anActivationRecordCarriesWhoWroteIt() {
        DefinitionActivation attributed =
                new DefinitionActivation(WorkflowScopes.DEFAULT, "wf", WorkflowScopes.DEFAULT, 1, NOW, ALICE);
        DefinitionActivation shortForm =
                new DefinitionActivation(WorkflowScopes.DEFAULT, "wf", WorkflowScopes.DEFAULT, 1, NOW);

        assertThat(attributed.activatedBy()).isEqualTo(ALICE);
        assertThat(attributed.activatedAt()).isEqualTo(NOW);
        assertThat(shortForm.activatedBy()).isNull();
        assertThat(shortForm)
                .isEqualTo(
                        new DefinitionActivation(WorkflowScopes.DEFAULT, "wf", WorkflowScopes.DEFAULT, 1, NOW, null));
    }

    @Test
    @DisplayName("an actor needs its actor and its attribution, and the system actor needs a reason")
    void anActorRequiresItsComponents() {
        assertThatThrownBy(() -> new DefinitionChangeActor(null, null, HistoryAttribution.ABSENT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                        new DefinitionChangeActor(new HistoryActor(HistoryActor.Kind.NONE, null, null), null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DefinitionChangeActor.system(null)).isInstanceOf(NullPointerException.class);
    }
}
