// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code metadata} carrier on {@link JobExecution}. Verifies that the field
 * holds a {@link DurableMetadata} namespaced document: {@code null} input is normalised to
 * {@link DurableMetadata#empty()}, namespace presence is queryable, and the copy-on-write methods
 * preserve metadata across the chain.
 */
class JobExecutionMetadataTest {

    @Test
    @DisplayName("metadata defaults to empty DurableMetadata when supplied as null")
    void nullMetadataNormalisedToEmpty() {
        JobExecution exec = newExec(null);
        assertTrue(exec.metadata().isEmpty(), "null metadata must normalise to DurableMetadata.empty()");
    }

    @Test
    @DisplayName("metadata defaults to empty DurableMetadata when supplied as empty")
    void emptyMetadataPersists() {
        JobExecution exec = newExec(DurableMetadata.empty());
        assertTrue(exec.metadata().isEmpty());
    }

    @Test
    @DisplayName("namespace body round-trips through the record")
    void namespaceBodyRoundTrips() {
        JsonObject body = new JsonObject().put("correlationId", "c-1").put("tenantId", "t-1");
        DurableMetadata meta = DurableMetadata.of("correlation", body);

        JobExecution exec = newExec(meta);

        assertTrue(exec.metadata().has("correlation"), "namespace must be present");
        assertEquals(
                "c-1",
                exec.metadata().body("correlation").orElseThrow().getString("correlationId"),
                "body field must round-trip");
    }

    @Test
    @DisplayName("DurableMetadata instance is preserved by reference through the record")
    void metadataInstancePreserved() {
        DurableMetadata meta = DurableMetadata.of("loc", new JsonObject().put("locale", "en"));
        JobExecution exec = newExec(meta);
        assertEquals(meta, exec.metadata(), "stored instance must equal the supplied one");
    }

    @Test
    @DisplayName("withState preserves metadata across the copy-on-write chain")
    void withStatePreservesMetadata() {
        DurableMetadata meta = DurableMetadata.of("correlation", new JsonObject().put("correlationId", "c-2"));
        JobExecution exec = newExec(meta);
        JobExecution updated = exec.withState(JobState.PROCESSING);
        assertEquals(meta, updated.metadata(), "metadata must survive withState");
    }

    @Test
    @DisplayName("metadata with multiple namespaces is preserved intact")
    void multipleNamespacesPreserved() {
        DurableMetadata meta = DurableMetadata.of("correlation", new JsonObject().put("id", "c-3"))
                .with("localization", new JsonObject().put("locale", "fi"));

        JobExecution exec = newExec(meta);

        assertTrue(exec.metadata().has("correlation"));
        assertTrue(exec.metadata().has("localization"));
        assertFalse(exec.metadata().has("other"));
    }

    // --- Helpers ---

    private static JobExecution newExec(DurableMetadata metadata) {
        return new JobExecution(
                UUID.randomUUID(),
                "logical-job-1",
                JobType.DELAYED,
                "test.handler",
                "default",
                JobState.ENQUEUED,
                0,
                5,
                "payload",
                0,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                metadata);
    }
}
