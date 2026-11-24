// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies how stored history rows are read into entries. */
class RowMappersTest {

    @Test
    @DisplayName("an actor kind this version does not know is read as no actor and does not fail the entry")
    void unknownActorKindIsReadAsNoActor() throws Exception {
        // Given a history row whose actor kind a newer version wrote
        Row row = mock(Row.class);
        when(row.getUUID("workflow_id")).thenReturn(UUID.randomUUID());
        when(row.getLong("sequence")).thenReturn(1L);
        when(row.getString("entry_type")).thenReturn("START");
        when(row.getValue("payload_json")).thenReturn("{}");
        when(row.getOffsetDateTime("recorded_at")).thenReturn(OffsetDateTime.now());
        when(row.getInteger("entry_version")).thenReturn(2);
        when(row.getJsonObject("actor"))
                .thenReturn(new JsonObject().put("kind", "ROBOT").put("value", "r-1"));

        // When the row is mapped
        WorkflowHistoryEntry entry = RowMappers.enrichedWorkflowHistoryEntry().map(row);

        // The row remains readable, and it names no actor.
        assertThat(entry.actor()).isNull();
    }

    @Test
    void aStoredActorThatIsNotAnObjectIsReadAsNoActor() {
        Row row = mock(Row.class);
        when(row.getJsonObject("actor")).thenThrow(ClassCastException.class);

        assertThat(PgWorkflowActor.read(row, "actor")).isNull();
    }

    @Test
    void aStoredActorIsReadForEachKindAndNoneAndNullReadAsNoActor() {
        Row row = mock(Row.class);
        when(row.getJsonObject("actor"))
                .thenReturn(new JsonObject().put("kind", "USER").put("value", "u-1"))
                .thenReturn(new JsonObject().put("kind", "SERVICE").put("value", "s-1"))
                .thenReturn(new JsonObject().put("kind", "SYSTEM").put("value", "timer"))
                .thenReturn(new JsonObject().put("kind", "ANONYMOUS").put("value", ""))
                .thenReturn(new JsonObject().put("kind", "NONE"))
                .thenReturn(null);

        assertThat(PgWorkflowActor.read(row, "actor")).isEqualTo(new WorkflowActor.User("u-1"));
        assertThat(PgWorkflowActor.read(row, "actor")).isEqualTo(new WorkflowActor.Service("s-1"));
        assertThat(PgWorkflowActor.read(row, "actor")).isEqualTo(new WorkflowActor.System("timer"));
        assertThat(PgWorkflowActor.read(row, "actor")).isEqualTo(new WorkflowActor.Anonymous());
        assertThat(PgWorkflowActor.read(row, "actor")).isNull();
        assertThat(PgWorkflowActor.read(row, "actor")).isNull();
    }

    @Test
    void anActorIsStoredAsItsKindAndValueAndNoActorAsNone() {
        assertThat(PgWorkflowActor.toJson(new WorkflowActor.User("u-1")))
                .isEqualTo(new JsonObject().put("kind", "USER").put("value", "u-1"));
        assertThat(PgWorkflowActor.toJson(new WorkflowActor.Anonymous()))
                .isEqualTo(new JsonObject().put("kind", "ANONYMOUS").put("value", ""));
        assertThat(PgWorkflowActor.toJsonOrNone(null)).isEqualTo(new JsonObject().put("kind", "NONE"));
    }
}
