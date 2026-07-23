// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowMigratedHistoryPayload}'s {@code commandCorrelationId} stitching field
 * (PRD FR-WF-CTX-050/051, AC-5, Contract Appendix C5).
 */
class WorkflowMigratedHistoryPayloadTest {

    private static final Instant MIGRATED_AT = Instant.parse("2026-05-08T12:00:00Z");

    private static WorkflowMigratedHistoryPayload payload(String commandCorrelationId) {
        return new WorkflowMigratedHistoryPayload(
                1L,
                "source-hash",
                2L,
                "target-hash",
                "source-step",
                "target-step",
                "dev.vertique.workflow.MyHandler",
                MIGRATED_AT,
                commandCorrelationId);
    }

    @Test
    @DisplayName("valid construction succeeds and commandCorrelationId is accessible")
    void validConstruction() {
        WorkflowMigratedHistoryPayload p = payload("corr-F");

        assertThat(p.sourceVersion()).isEqualTo(1L);
        assertThat(p.commandCorrelationId()).isEqualTo("corr-F");
    }

    @Test
    @DisplayName("commandCorrelationId is nullable")
    void nullCommandCorrelationIdAllowed() {
        WorkflowMigratedHistoryPayload p = payload(null);

        assertThat(p.commandCorrelationId()).isNull();
    }

    @Test
    @DisplayName("deserializing a legacy JSON payload without commandCorrelationId yields null (additive field)")
    void deserializeLegacyJsonWithoutField_commandCorrelationIdIsNull() {
        JsonObject legacyJson = new JsonObject(Json.encode(payload("should-be-stripped")));
        legacyJson.remove("commandCorrelationId");

        WorkflowMigratedHistoryPayload p = Json.decodeValue(legacyJson.encode(), WorkflowMigratedHistoryPayload.class);

        assertThat(p.commandCorrelationId())
                .as("an old row with no commandCorrelationId key must deserialize with a null field"
                        + " (additive Jackson compatibility)")
                .isNull();
        assertThat(p.sourceVersion()).isEqualTo(1L);
        assertThat(p.targetVersion()).isEqualTo(2L);
    }
}
