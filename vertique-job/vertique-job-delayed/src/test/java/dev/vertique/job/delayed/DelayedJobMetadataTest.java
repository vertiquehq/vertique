// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code metadata} builder field on {@link DelayedJob}. The field carries a
 * {@link DurableMetadata} namespaced document; {@code DelayedJobService.toExecution(...)} later
 * merges the document with currently-bound ambient durable context via
 * {@code DurableContextPropagator.mergeCaptured(...)} and persists the result into
 * {@code job_executions.metadata JSONB} via {@link DurableMetadata#toCarrier()}.
 */
class DelayedJobMetadataTest {

    @Test
    @DisplayName("metadata defaults to DurableMetadata.empty() when not set on the builder")
    void defaultMetadataIsEmpty() {
        DelayedJob job = DelayedJob.builder().handler("h").build();
        assertTrue(job.metadata().isEmpty(), "default metadata must be DurableMetadata.empty()");
    }

    @Test
    @DisplayName("builder.metadata(DurableMetadata) preserves the supplied namespace")
    void builderPreservesSuppliedMetadata() {
        DurableMetadata meta = DurableMetadata.of(
                "correlation", new JsonObject().put("correlationId", "c-1").put("tenantId", "t-1"));
        DelayedJob job = DelayedJob.builder().handler("h").metadata(meta).build();

        assertTrue(job.metadata().has("correlation"), "correlation namespace must be present");
        assertEquals(
                "c-1",
                job.metadata().body("correlation").orElseThrow().getString("correlationId"),
                "correlationId must round-trip");
        assertEquals(
                "t-1",
                job.metadata().body("correlation").orElseThrow().getString("tenantId"),
                "tenantId must round-trip");
    }

    @Test
    @DisplayName("builder.metadata preserves multiple namespaces")
    void builderPreservesMultipleNamespaces() {
        DurableMetadata meta = DurableMetadata.of("correlation", new JsonObject().put("id", "c-2"))
                .with("localization", new JsonObject().put("locale", "en"));
        DelayedJob job = DelayedJob.builder().handler("h").metadata(meta).build();

        assertEquals(meta, job.metadata());
    }
}
