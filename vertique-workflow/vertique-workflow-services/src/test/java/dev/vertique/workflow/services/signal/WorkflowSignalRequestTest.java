// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.signal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkflowSignalRequest} (PRD-WF-007, Contract Appendix C4).
 *
 * <p>Verifies that the existing {@code instance(...)}/{@code branch(...)} factory arities remain
 * source-compatible (NFR-WF-CTX-006) after the additive {@code metadata} component, that the new
 * metadata-bearing overloads set the carrier correctly, and that a non-null {@code metadata} carrier
 * survives a Jackson wire round-trip unchanged (FR-WF-CTX-041).
 */
class WorkflowSignalRequestTest {

    private static final WorkflowInstanceId WORKFLOW_ID = new WorkflowInstanceId(UUID.randomUUID());

    // --- instance(...) factory compatibility ---

    @Test
    @DisplayName("instance(): existing 4-arg call shape defaults metadata to null")
    void instanceFactory_existingArity_metadataDefaultsNull() {
        WorkflowSignalRequest req = WorkflowSignalRequest.instance(WORKFLOW_ID, "approve", "payload", "dedup-1");

        assertThat(req.metadata()).isNull();
        assertThat(req.forkStepId()).isNull();
        assertThat(req.branchId()).isNull();
    }

    @Test
    @DisplayName("instance(): new metadata-bearing overload sets the carrier")
    void instanceFactory_newMetadataOverload_setsCarrier() {
        JsonObject carrier = new JsonObject().put("context", new JsonObject().put("tenant", new JsonObject()));

        WorkflowSignalRequest req =
                WorkflowSignalRequest.instance(WORKFLOW_ID, "approve", "payload", "dedup-1", carrier);

        assertThat(req.metadata()).isEqualTo(carrier);
        assertThat(req.forkStepId()).isNull();
        assertThat(req.branchId()).isNull();
    }

    // --- branch(...) factory compatibility ---

    @Test
    @DisplayName("branch(): existing 6-arg call shape defaults metadata to null")
    void branchFactory_existingArity_metadataDefaultsNull() {
        WorkflowSignalRequest req =
                WorkflowSignalRequest.branch(WORKFLOW_ID, "approve", "payload", "dedup-1", "fork-1", "branch-a");

        assertThat(req.metadata()).isNull();
        assertThat(req.forkStepId()).isEqualTo("fork-1");
        assertThat(req.branchId()).isEqualTo("branch-a");
    }

    @Test
    @DisplayName("branch(): new metadata-bearing overload sets the carrier")
    void branchFactory_newMetadataOverload_setsCarrier() {
        JsonObject carrier = new JsonObject().put("context", new JsonObject().put("tenant", new JsonObject()));

        WorkflowSignalRequest req = WorkflowSignalRequest.branch(
                WORKFLOW_ID, "approve", "payload", "dedup-1", "fork-1", "branch-a", carrier);

        assertThat(req.metadata()).isEqualTo(carrier);
        assertThat(req.forkStepId()).isEqualTo("fork-1");
        assertThat(req.branchId()).isEqualTo("branch-a");
    }

    // --- Wire round-trip (FR-WF-CTX-041) ---

    @Test
    @DisplayName("wire round-trip: a non-null metadata context object survives Jackson serialize/deserialize")
    void wireRoundTrip_metadataContextObject_serializesAndDeserializes() {
        JsonObject carrier =
                new JsonObject().put("context", new JsonObject().put("tenant", new JsonObject().put("tenantId", "T1")));
        WorkflowSignalRequest req =
                WorkflowSignalRequest.instance(WORKFLOW_ID, "approve", "payload-value", "dedup-1", carrier);

        String encoded = Json.encode(req);
        WorkflowSignalRequest decoded = Json.decodeValue(encoded, WorkflowSignalRequest.class);

        assertThat(decoded.metadata()).isEqualTo(carrier);
        assertThat(decoded.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(decoded.signalName()).isEqualTo("approve");
        assertThat(decoded.dedupKey()).isEqualTo("dedup-1");
    }

    @Test
    @DisplayName("wire round-trip: a null metadata carrier round-trips as null")
    void wireRoundTrip_nullMetadata_roundTripsAsNull() {
        WorkflowSignalRequest req = WorkflowSignalRequest.instance(WORKFLOW_ID, "approve", "payload-value", "dedup-1");

        String encoded = Json.encode(req);
        WorkflowSignalRequest decoded = Json.decodeValue(encoded, WorkflowSignalRequest.class);

        assertThat(decoded.metadata()).isNull();
    }
}
