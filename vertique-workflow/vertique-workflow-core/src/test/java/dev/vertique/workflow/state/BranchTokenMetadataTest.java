// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link BranchToken#metadata()} field invariants after the durable context
 * propagation migration (FR-CTX-178):
 *
 * <ul>
 *   <li>Null metadata is normalised to {@link DurableMetadata#empty()}.
 *   <li>Non-null metadata is preserved by value equality.
 *   <li>Immutability is structural — {@link DurableMetadata} has no {@code put} method.
 *   <li>The {@code with*} updaters pass metadata through unchanged.
 * </ul>
 */
@DisplayName("BranchToken.metadata field invariants")
class BranchTokenMetadataTest {

    private static final WorkflowInstanceId WF = new WorkflowInstanceId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-05-25T12:00:00Z");

    // --- Null normalisation ---

    @Nested
    @DisplayName("null metadata normalisation")
    class NullNormalisation {

        @Test
        @DisplayName("null metadata is normalised to empty()")
        void nullMetadataNormalisedToEmpty() {
            BranchToken token = buildToken(null);
            assertTrue(token.metadata().isEmpty(), "null metadata must normalise to DurableMetadata.empty()");
        }
    }

    // --- Value-equality round-trip ---

    @Nested
    @DisplayName("namespace body round-trip")
    class NamespaceRoundTrip {

        @Test
        @DisplayName("single-namespace body round-trips through the record")
        void singleNamespaceBodyRoundTrips() {
            DurableMetadata meta = DurableMetadata.of("test", new JsonObject().put("corr", "c-1"));
            BranchToken token = buildToken(meta);
            assertTrue(token.metadata().has("test"), "namespace must be present");
            assertEquals(
                    "c-1", token.metadata().body("test").orElseThrow().getString("corr"), "body field must round-trip");
        }

        @Test
        @DisplayName("DurableMetadata instance is preserved by value through the record")
        void metadataInstancePreservedByValue() {
            DurableMetadata meta = DurableMetadata.of("test", new JsonObject().put("corr", "c-1"));
            BranchToken token = buildToken(meta);
            assertEquals(meta, token.metadata(), "stored value must equal the supplied one");
        }

        @Test
        @DisplayName("multi-namespace metadata round-trips through the record")
        void multiNamespaceMetadataRoundTrips() {
            DurableMetadata meta = DurableMetadata.of(
                            "test", new JsonObject().put("corr", "c-1").put("tenant", "t-1"))
                    .with("loc", new JsonObject().put("locale", "fi"));
            BranchToken token = buildToken(meta);
            assertTrue(token.metadata().has("test"));
            assertTrue(token.metadata().has("loc"));
            assertFalse(token.metadata().has("other"));
        }
    }

    // --- with* updaters preserve metadata ---

    @Nested
    @DisplayName("with* updaters preserve metadata")
    class WithUpdaterPreservation {

        @Test
        @DisplayName("withStep passes metadata through unchanged")
        void withStepPreservesMetadata() {
            DurableMetadata meta = DurableMetadata.of("test", new JsonObject().put("corr", "c-1"));
            BranchToken before = buildToken(meta);
            BranchToken after = before.withStep("next-step", before.version() + 1, NOW);
            assertEquals(meta, after.metadata(), "metadata must survive withStep");
        }

        @Test
        @DisplayName("withStatus passes metadata through unchanged")
        void withStatusPreservesMetadata() {
            DurableMetadata meta = DurableMetadata.of("test", new JsonObject().put("corr", "c-2"));
            BranchToken before = buildToken(meta);
            BranchToken after = before.withStatus(BranchStatus.COMPLETED, before.version() + 1, NOW);
            assertEquals(meta, after.metadata(), "metadata must survive withStatus");
        }

        @Test
        @DisplayName("withWait passes metadata through unchanged")
        void withWaitPreservesMetadata() {
            DurableMetadata meta = DurableMetadata.of("test", new JsonObject().put("corr", "c-3"));
            BranchToken before = buildToken(meta);
            BranchToken after = before.withWait(WaitType.SIGNAL, "my-signal", null, before.version() + 1, NOW);
            assertEquals(meta, after.metadata(), "metadata must survive withWait");
        }

        @Test
        @DisplayName("withClearedWait passes metadata through unchanged")
        void withClearedWaitPreservesMetadata() {
            DurableMetadata meta = DurableMetadata.of("test", new JsonObject().put("corr", "c-4"));
            BranchToken before = buildToken(meta).withWait(WaitType.SIGNAL, "sig", null, 1L, NOW);
            BranchToken after = before.withClearedWait(BranchStatus.RUNNING, "next-step", NOW);
            assertEquals(meta, after.metadata(), "metadata must survive withClearedWait");
        }
    }

    // --- Helpers ---

    /**
     * Builds a {@link BranchToken} with fixed base fields and the supplied metadata.
     *
     * @param metadata the durable metadata (may be null — will be normalised to empty)
     * @return a constructed branch token
     */
    private static BranchToken buildToken(DurableMetadata metadata) {
        return new BranchToken(
                UUID.randomUUID(),
                WF,
                "fork",
                "branch-a",
                "step-1",
                BranchStatus.RUNNING,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                3,
                null,
                null,
                null,
                null,
                0L,
                NOW,
                NOW,
                metadata);
    }
}
