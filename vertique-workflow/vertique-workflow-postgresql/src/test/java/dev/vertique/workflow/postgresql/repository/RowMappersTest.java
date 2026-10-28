// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.state.HistoryActor;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.sqlclient.Row;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies how stored history rows are read into entries. */
class RowMappersTest {

    @Test
    @DisplayName("an actor kind this version does not know is read as none and does not fail the entry")
    void unknownActorKindIsReadAsNone() throws Exception {
        // Given a history row whose actor kind a newer version wrote
        Row row = mock(Row.class);
        when(row.getUUID("workflow_id")).thenReturn(UUID.randomUUID());
        when(row.getLong("sequence")).thenReturn(1L);
        when(row.getString("entry_type")).thenReturn("START");
        when(row.getValue("payload_json")).thenReturn("{}");
        when(row.getOffsetDateTime("recorded_at")).thenReturn(OffsetDateTime.now());
        when(row.getInteger("entry_version")).thenReturn(2);
        when(row.getString("actor_kind")).thenReturn("ROBOT");
        when(row.getString("actor_id")).thenReturn("r-1");

        // When the row is mapped
        WorkflowHistoryEntry entry = RowMappers.enrichedWorkflowHistoryEntry().map(row);

        // Then the entry is read, with the actor kind none
        assertThat(entry.actor()).isNotNull();
        assertThat(entry.actor().kind()).isEqualTo(HistoryActor.Kind.NONE);
    }
}
