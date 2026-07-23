// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata.MergePolicy;
import dev.vertique.core.exception.MalformedDurableMetadataException;
import io.vertx.core.json.JsonObject;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DurableMetadata}: the immutable, namespaced durable-context document.
 * Focuses on the deep-copy immutability guarantee (the {@code JsonObject.getMap()} aliasing trap the
 * design must avoid), namespace accessors, merge policies, and the {@code context} carrier wrapper.
 */
class DurableMetadataTest {

    private static JsonObject body(String key, String value) {
        return new JsonObject().put(key, value);
    }

    @Nested
    @DisplayName("construction + accessors")
    class Construction {

        @Test
        @DisplayName("empty() has no namespaces")
        void empty() {
            DurableMetadata md = DurableMetadata.empty();
            assertTrue(md.isEmpty());
            assertTrue(md.namespaces().isEmpty());
            assertFalse(md.has("correlation"));
            assertTrue(md.body("correlation").isEmpty());
        }

        @Test
        @DisplayName("of(namespace, body) exposes the namespace and its body")
        void of() {
            DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"));
            assertFalse(md.isEmpty());
            assertEquals(java.util.Set.of("correlation"), md.namespaces());
            assertTrue(md.has("correlation"));
            assertEquals(body("requestId", "r1"), md.body("correlation").orElseThrow());
        }

        @Test
        @DisplayName("of() rejects null/blank namespace and null body")
        void ofValidation() {
            assertThrows(RuntimeException.class, () -> DurableMetadata.of(null, body("a", "b")));
            assertThrows(RuntimeException.class, () -> DurableMetadata.of("  ", body("a", "b")));
            assertThrows(RuntimeException.class, () -> DurableMetadata.of("correlation", null));
        }

        @Test
        @DisplayName("namespaces() is unmodifiable")
        void namespacesUnmodifiable() {
            DurableMetadata md = DurableMetadata.of("correlation", body("a", "b"));
            assertThrows(
                    UnsupportedOperationException.class, () -> md.namespaces().add("x"));
        }
    }

    @Nested
    @DisplayName("deep-copy immutability")
    class Immutability {

        @Test
        @DisplayName("of() deep-copies the body in (mutating source afterwards has no effect)")
        void deepCopyIn() {
            JsonObject source = body("requestId", "r1");
            DurableMetadata md = DurableMetadata.of("correlation", source);
            source.put("requestId", "MUTATED");
            source.put("added", "x");
            assertEquals("r1", md.body("correlation").orElseThrow().getString("requestId"));
            assertFalse(md.body("correlation").orElseThrow().containsKey("added"));
        }

        @Test
        @DisplayName("body() deep-copies out (mutating the returned object has no effect)")
        void deepCopyOut() {
            DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"));
            md.body("correlation").orElseThrow().put("requestId", "MUTATED");
            assertEquals("r1", md.body("correlation").orElseThrow().getString("requestId"));
        }

        @Test
        @DisplayName("nested objects are deep-copied (not aliased)")
        void nestedDeepCopy() {
            JsonObject nested = new JsonObject().put("inner", "v1");
            JsonObject source = new JsonObject().put("nested", nested);
            DurableMetadata md = DurableMetadata.of("correlation", source);
            nested.put("inner", "MUTATED");
            assertEquals(
                    "v1",
                    md.body("correlation").orElseThrow().getJsonObject("nested").getString("inner"));
        }

        @Test
        @DisplayName("toJson() returns a deep copy; mutating it does not affect the document")
        void toJsonDeepCopy() {
            DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"));
            JsonObject json = md.toJson();
            json.getJsonObject("correlation").put("requestId", "MUTATED");
            assertEquals("r1", md.body("correlation").orElseThrow().getString("requestId"));
        }
    }

    @Nested
    @DisplayName("with()")
    class With {

        @Test
        @DisplayName("with() returns a new instance and leaves the original unchanged")
        void withImmutable() {
            DurableMetadata base = DurableMetadata.of("correlation", body("a", "1"));
            DurableMetadata extended = base.with("localization", body("locale", "en-US"));
            assertEquals(java.util.Set.of("correlation"), base.namespaces());
            assertEquals(java.util.Set.of("correlation", "localization"), extended.namespaces());
        }

        @Test
        @DisplayName("with() replaces an existing namespace")
        void withReplace() {
            DurableMetadata base = DurableMetadata.of("correlation", body("a", "1"));
            DurableMetadata replaced = base.with("correlation", body("a", "2"));
            assertEquals("2", replaced.body("correlation").orElseThrow().getString("a"));
        }
    }

    @Nested
    @DisplayName("merge()")
    class Merge {

        @Test
        @DisplayName("CALLER_WINS unions namespaces and keeps the receiver on conflict")
        void callerWins() {
            DurableMetadata caller = DurableMetadata.of("correlation", body("v", "caller"));
            DurableMetadata other =
                    DurableMetadata.of("correlation", body("v", "other")).with("localization", body("locale", "en"));
            DurableMetadata merged = caller.merge(other, MergePolicy.CALLER_WINS);
            assertEquals(java.util.Set.of("correlation", "localization"), merged.namespaces());
            assertEquals("caller", merged.body("correlation").orElseThrow().getString("v"));
            assertEquals("en", merged.body("localization").orElseThrow().getString("locale"));
        }

        @Test
        @DisplayName("FAIL_ON_CONFLICT throws when a namespace is present on both sides")
        void failOnConflict() {
            DurableMetadata a = DurableMetadata.of("correlation", body("v", "1"));
            DurableMetadata b = DurableMetadata.of("correlation", body("v", "2"));
            assertThrows(IllegalStateException.class, () -> a.merge(b, MergePolicy.FAIL_ON_CONFLICT));
        }

        @Test
        @DisplayName("FAIL_ON_CONFLICT unions disjoint namespaces")
        void failOnConflictDisjoint() {
            DurableMetadata a = DurableMetadata.of("correlation", body("v", "1"));
            DurableMetadata b = DurableMetadata.of("localization", body("locale", "en"));
            DurableMetadata merged = a.merge(b, MergePolicy.FAIL_ON_CONFLICT);
            assertEquals(java.util.Set.of("correlation", "localization"), merged.namespaces());
        }
    }

    @Nested
    @DisplayName("JSON round-trips")
    class RoundTrips {

        @Test
        @DisplayName("toJson() / fromJson() round-trip preserves equality")
        void jsonRoundTrip() {
            DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"))
                    .with("localization", body("locale", "en-US"));
            assertEquals(md, DurableMetadata.fromJson(md.toJson()));
        }

        @Test
        @DisplayName("toCarrier() wraps the namespaces under the context key")
        void carrierShape() {
            DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"));
            JsonObject carrier = md.toCarrier();
            assertTrue(carrier.containsKey(DurableMetadata.CONTEXT_KEY));
            assertEquals(md.toJson(), carrier.getJsonObject(DurableMetadata.CONTEXT_KEY));
        }

        @Test
        @DisplayName("toCarrier() / fromCarrier() round-trip preserves equality")
        void carrierRoundTrip() {
            DurableMetadata md = DurableMetadata.of("localization", body("locale", "sv-FI"));
            assertEquals(md, DurableMetadata.fromCarrier(md.toCarrier()));
        }

        @Test
        @DisplayName("fromCarrier() with no context section yields empty")
        void carrierMissingContext() {
            DurableMetadata md = DurableMetadata.fromCarrier(new JsonObject().put("delivery", new JsonObject()));
            assertTrue(md.isEmpty());
        }

        @Test
        @DisplayName("fromJson(null) and fromCarrier(null) yield empty")
        void nullInputs() {
            assertTrue(DurableMetadata.fromJson(null).isEmpty());
            assertTrue(DurableMetadata.fromCarrier(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("carrier validation at decode time")
    class CarrierValidation {

        @Test
        @DisplayName("fromCarrier() rejects a context section that is not a JSON object")
        void fromCarrierRejectsNonObjectContextSection() {
            JsonObject carrier = new JsonObject().put(DurableMetadata.CONTEXT_KEY, "x");
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromCarrier(carrier));
        }

        @Test
        @DisplayName("fromCarrier() rejects a namespace body that is not a JSON object")
        void fromCarrierRejectsNonObjectNamespaceBody() {
            JsonObject carrier =
                    new JsonObject().put(DurableMetadata.CONTEXT_KEY, new JsonObject().put("tenant", "oops"));
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromCarrier(carrier));
        }

        @Test
        @DisplayName("fromJson() rejects a namespace body that is not a JSON object")
        void fromJsonRejectsNonObjectNamespaceBody() {
            JsonObject namespaces = new JsonObject().put("correlation", 42);
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromJson(namespaces));
        }

        @Test
        @DisplayName("fromCarrier() with a well-formed carrier round-trips unchanged (over-strictness guard)")
        void fromCarrierWellFormedRoundTripsUnchanged() {
            DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"))
                    .with("localization", body("locale", "en-US"));
            assertEquals(md, DurableMetadata.fromCarrier(md.toCarrier()));
        }
    }

    @Test
    @DisplayName("body() returns Optional.empty for an absent namespace")
    void absentNamespace() {
        DurableMetadata md = DurableMetadata.of("correlation", body("a", "b"));
        assertEquals(Optional.empty(), md.body("localization"));
    }

    @Nested
    @DisplayName("defense-in-depth decode bounds")
    class DecodeBounds {

        @Test
        @DisplayName("fromJson() rejects a namespaces document with more than 64 namespaces")
        void fromJsonRejectsTooManyNamespaces() {
            JsonObject namespaces = new JsonObject();
            for (int i = 0; i < 65; i++) {
                namespaces.put("ns" + i, body("k", "v"));
            }
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromJson(namespaces));
        }

        @Test
        @DisplayName("fromCarrier() rejects a namespaces document with more than 64 namespaces")
        void fromCarrierRejectsTooManyNamespaces() {
            JsonObject namespaces = new JsonObject();
            for (int i = 0; i < 65; i++) {
                namespaces.put("ns" + i, body("k", "v"));
            }
            JsonObject carrier = new JsonObject().put(DurableMetadata.CONTEXT_KEY, namespaces);
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromCarrier(carrier));
        }

        @Test
        @DisplayName("fromJson() rejects a namespaces document whose encoded size exceeds 256 KiB")
        void fromJsonRejectsOversizedDocument() {
            // A single namespace with a large string body pushes the encoded document past 256 KiB.
            String bigValue = "x".repeat(300_000);
            JsonObject namespaces = new JsonObject().put("correlation", body("blob", bigValue));
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromJson(namespaces));
        }

        @Test
        @DisplayName("fromCarrier() rejects a namespaces document whose encoded size exceeds 256 KiB")
        void fromCarrierRejectsOversizedDocument() {
            String bigValue = "x".repeat(300_000);
            JsonObject namespaces = new JsonObject().put("correlation", body("blob", bigValue));
            JsonObject carrier = new JsonObject().put(DurableMetadata.CONTEXT_KEY, namespaces);
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromCarrier(carrier));
        }

        @Test
        @DisplayName("fromJson() rejects a namespace body nested more than 32 levels deep")
        void fromJsonRejectsExcessiveNestingDepth() {
            // Build a JsonObject nested 33 levels deep: {"l0": {"l1": {"l2": ... {"leaf": "v"} ...}}}
            JsonObject innermost = new JsonObject().put("leaf", "v");
            JsonObject current = innermost;
            for (int i = 0; i < 33; i++) {
                current = new JsonObject().put("l" + i, current);
            }
            JsonObject namespaces = new JsonObject().put("correlation", current);
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromJson(namespaces));
        }

        @Test
        @DisplayName("fromCarrier() rejects a namespace body nested more than 32 levels deep")
        void fromCarrierRejectsExcessiveNestingDepth() {
            JsonObject innermost = new JsonObject().put("leaf", "v");
            JsonObject current = innermost;
            for (int i = 0; i < 33; i++) {
                current = new JsonObject().put("l" + i, current);
            }
            JsonObject namespaces = new JsonObject().put("correlation", current);
            JsonObject carrier = new JsonObject().put(DurableMetadata.CONTEXT_KEY, namespaces);
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromCarrier(carrier));
        }

        @Test
        @DisplayName("fromJson() rejects nesting depth exceeded via a JsonArray path, not just JsonObject")
        void fromJsonRejectsExcessiveNestingDepthThroughArray() {
            // Alternate object/array nesting to prove the walk descends into arrays too.
            Object current = "v";
            for (int i = 0; i < 33; i++) {
                if (i % 2 == 0) {
                    current = new JsonObject().put("k", current);
                } else {
                    current = new io.vertx.core.json.JsonArray().add(current);
                }
            }
            JsonObject namespaces = new JsonObject().put("correlation", new JsonObject().put("wrap", current));
            assertThrows(MalformedDurableMetadataException.class, () -> DurableMetadata.fromJson(namespaces));
        }

        @Test
        @DisplayName("a comfortably-in-bounds document (namespaces, size, depth) round-trips unchanged")
        void inBoundsDocumentRoundTripsUnchanged() {
            JsonObject namespaces = new JsonObject();
            for (int i = 0; i < 10; i++) {
                namespaces.put("ns" + i, body("k", "v" + i));
            }
            // Modest nesting, well under the 32-level bound.
            JsonObject nested = new JsonObject().put("a", new JsonObject().put("b", new JsonObject().put("c", "d")));
            namespaces.put("nested", nested);

            DurableMetadata decoded = DurableMetadata.fromJson(namespaces);
            assertEquals(namespaces, decoded.toJson());

            JsonObject carrier = new JsonObject().put(DurableMetadata.CONTEXT_KEY, namespaces);
            assertEquals(namespaces, DurableMetadata.fromCarrier(carrier).toJson());
        }
    }
}
