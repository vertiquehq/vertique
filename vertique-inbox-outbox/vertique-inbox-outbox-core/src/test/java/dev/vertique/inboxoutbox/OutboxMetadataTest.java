// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.exception.MalformedDurableMetadataException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxMetadata} decode-time shape validation.
 *
 * <p>Focuses on the {@link #fromJson(JsonObject)} decode path guarding both the {@code context} and
 * {@code delivery} sub-documents against malformed (e.g. DB-tampered) shapes, and on the well-formed
 * round-trip continuing to work unchanged.
 */
class OutboxMetadataTest {

    @Nested
    @DisplayName("fromJson() shape guard")
    class ShapeGuard {

        @Test
        @DisplayName("fromJson() throws MalformedDurableMetadataException when context is not a JSON object")
        void fromJsonRejectsNonObjectContext() {
            JsonObject metadata = new JsonObject().put(OutboxMetadata.CONTEXT_KEY, "oops");
            assertThrows(MalformedDurableMetadataException.class, () -> OutboxMetadata.fromJson(metadata));
        }

        @Test
        @DisplayName("fromJson() throws MalformedDurableMetadataException when delivery is not a JSON object")
        void fromJsonRejectsNonObjectDelivery() {
            JsonObject metadata = new JsonObject().put(OutboxMetadata.DELIVERY_KEY, 42);
            assertThrows(MalformedDurableMetadataException.class, () -> OutboxMetadata.fromJson(metadata));
        }

        @Test
        @DisplayName("fromJson() exception message names the offending key")
        void fromJsonMessageNamesKey() {
            JsonObject metadata = new JsonObject().put(OutboxMetadata.CONTEXT_KEY, "oops");
            MalformedDurableMetadataException ex =
                    assertThrows(MalformedDurableMetadataException.class, () -> OutboxMetadata.fromJson(metadata));
            assertTrue(ex.getMessage().contains(OutboxMetadata.CONTEXT_KEY));
        }
    }

    @Nested
    @DisplayName("fromJson() well-formed round-trip")
    class RoundTrip {

        @Test
        @DisplayName("well-formed metadata round-trips through toJson()/fromJson() unchanged")
        void wellFormedRoundTrip() {
            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("requestId", "r1"));
            OutboxDeliveryMetadata delivery =
                    new OutboxDeliveryMetadata(java.util.Optional.empty(), java.util.Optional.empty());
            OutboxMetadata original = new OutboxMetadata(context, delivery);

            OutboxMetadata roundTripped = OutboxMetadata.fromJson(original.toJson());

            assertEquals(original.context(), roundTripped.context());
            assertEquals(original.delivery(), roundTripped.delivery());
        }

        @Test
        @DisplayName("null metadata yields empty()")
        void nullMetadataYieldsEmpty() {
            assertEquals(OutboxMetadata.empty(), OutboxMetadata.fromJson(null));
        }

        @Test
        @DisplayName("empty metadata object yields empty()")
        void emptyMetadataYieldsEmpty() {
            assertEquals(OutboxMetadata.empty(), OutboxMetadata.fromJson(new JsonObject()));
        }
    }
}
