// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.payload.PayloadKind;
import dev.vertique.core.payload.PayloadSource;
import dev.vertique.core.payload.PayloadSources;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaDispatchContext} copy-on-write semantics, defensive copying of
 * headers and attributes, {@code null} map handling, and the {@code rawEvidence} payload source.
 */
class KafkaDispatchContextTest {

    /** Creates a minimal context for testing, with mutable maps so we can test defensive copies. */
    private static KafkaDispatchContext<String> minimalContext(
            Map<String, String> headers, Map<String, Object> attributes) {
        return new KafkaDispatchContext<>(
                "test-consumer",
                "test.topic",
                0,
                100L,
                "key",
                "value",
                PayloadSources.buffered(new byte[0], null),
                headers,
                1_700_000_000_000L,
                0,
                false,
                attributes);
    }

    // --- withFiltered() ---

    @Nested
    @DisplayName("withFiltered()")
    class WithFiltered {

        @Test
        @DisplayName("withFiltered(true) returns a new instance with filtered=true")
        void withFilteredTrueReturnsNewInstanceWithFilteredTrue() {
            KafkaDispatchContext<String> original = minimalContext(Map.of(), Map.of());
            KafkaDispatchContext<String> updated = original.withFiltered(true);

            assertTrue(updated.filtered());
        }

        @Test
        @DisplayName("withFiltered(true) does not modify the original instance")
        void withFilteredTrueDoesNotModifyOriginal() {
            KafkaDispatchContext<String> original = minimalContext(Map.of(), Map.of());
            original.withFiltered(true);

            assertFalse(original.filtered(), "Original context must remain unchanged");
        }

        @Test
        @DisplayName("withFiltered() returns a distinct instance from the original")
        void withFilteredReturnsDistinctInstance() {
            KafkaDispatchContext<String> original = minimalContext(Map.of(), Map.of());
            KafkaDispatchContext<String> updated = original.withFiltered(true);

            assertNotSame(original, updated);
        }

        @Test
        @DisplayName("withFiltered() preserves all other fields unchanged")
        void withFilteredPreservesOtherFields() {
            KafkaDispatchContext<String> original = minimalContext(Map.of("h", "v"), Map.of("attr", "attrValue"));
            KafkaDispatchContext<String> updated = original.withFiltered(true);

            assertEquals("test-consumer", updated.consumerName());
            assertEquals("test.topic", updated.topic());
            assertEquals(0, updated.partition());
            assertEquals(100L, updated.offset());
            assertEquals("key", updated.key());
            assertEquals("value", updated.value());
            assertEquals(0, updated.retryCount());
            assertEquals("v", updated.headers().get("h"));
            assertEquals("attrValue", updated.attributes().get("attr"));
        }

        @Test
        @DisplayName("withFiltered() preserves rawEvidence as the same instance")
        void withFilteredPreservesRawEvidence() {
            byte[] wireBytes = {1, 2, 3};
            PayloadSource evidence = PayloadSources.buffered(wireBytes, null);
            KafkaDispatchContext<String> original = new KafkaDispatchContext<>(
                    "test-consumer",
                    "test.topic",
                    0,
                    100L,
                    "key",
                    "value",
                    evidence,
                    Map.of(),
                    1_700_000_000_000L,
                    0,
                    false,
                    Map.of());

            KafkaDispatchContext<String> updated = original.withFiltered(true);

            assertSame(evidence, updated.rawEvidence(), "withFiltered must preserve rawEvidence instance");
        }
    }

    // --- withAttribute() ---

    @Nested
    @DisplayName("withAttribute()")
    class WithAttribute {

        @Test
        @DisplayName("withAttribute() returns a new instance containing the added attribute")
        void withAttributeReturnsNewInstanceWithAttribute() {
            KafkaDispatchContext<String> original = minimalContext(Map.of(), Map.of());
            KafkaDispatchContext<String> updated = original.withAttribute("trace-id", "abc123");

            assertEquals("abc123", updated.attributes().get("trace-id"));
        }

        @Test
        @DisplayName("withAttribute() does not modify the original instance")
        void withAttributeDoesNotModifyOriginal() {
            KafkaDispatchContext<String> original = minimalContext(Map.of(), Map.of());
            original.withAttribute("trace-id", "abc123");

            assertFalse(original.attributes().containsKey("trace-id"), "Original attributes must remain unchanged");
        }

        @Test
        @DisplayName("withAttribute() returns a distinct instance from the original")
        void withAttributeReturnsDistinctInstance() {
            KafkaDispatchContext<String> original = minimalContext(Map.of(), Map.of());
            KafkaDispatchContext<String> updated = original.withAttribute("key", "value");

            assertNotSame(original, updated);
        }

        @Test
        @DisplayName("withAttribute() accumulates multiple attributes across chained calls")
        void chainedWithAttributeCalls() {
            KafkaDispatchContext<String> original = minimalContext(Map.of(), Map.of());
            KafkaDispatchContext<String> updated =
                    original.withAttribute("k1", "v1").withAttribute("k2", "v2");

            assertEquals("v1", updated.attributes().get("k1"));
            assertEquals("v2", updated.attributes().get("k2"));
        }

        @Test
        @DisplayName("withAttribute() preserves rawEvidence as the same instance")
        void withAttributePreservesRawEvidence() {
            byte[] wireBytes = {7, 8, 9};
            PayloadSource evidence = PayloadSources.buffered(wireBytes, null);
            KafkaDispatchContext<String> original = new KafkaDispatchContext<>(
                    "test-consumer",
                    "test.topic",
                    0,
                    100L,
                    "key",
                    "value",
                    evidence,
                    Map.of(),
                    1_700_000_000_000L,
                    0,
                    false,
                    Map.of());

            KafkaDispatchContext<String> updated = original.withAttribute("extra", "data");

            assertSame(evidence, updated.rawEvidence(), "withAttribute must preserve rawEvidence instance");
        }
    }

    // --- rawEvidence() ---

    @Nested
    @DisplayName("rawEvidence()")
    class RawEvidence {

        @Test
        @DisplayName("rawEvidence() returns a BUFFERED PayloadSource")
        void rawEvidenceIsBuffered() {
            byte[] wireBytes = {10, 20, 30};
            KafkaDispatchContext<String> ctx = new KafkaDispatchContext<>(
                    "consumer",
                    "topic",
                    0,
                    0L,
                    null,
                    "value",
                    PayloadSources.buffered(wireBytes, null),
                    Map.of(),
                    0L,
                    0,
                    false,
                    Map.of());

            assertEquals(PayloadKind.BUFFERED, ctx.rawEvidence().kind());
        }

        @Test
        @DisplayName("rawEvidence().bufferedStream() yields the original wire bytes (no eager copy)")
        void rawEvidenceBufferedStreamYieldsOriginalBytes() throws IOException {
            byte[] wireBytes = {10, 20, 30, 40, 50};
            KafkaDispatchContext<String> ctx = new KafkaDispatchContext<>(
                    "consumer",
                    "topic",
                    0,
                    0L,
                    null,
                    "value",
                    PayloadSources.buffered(wireBytes, null),
                    Map.of(),
                    0L,
                    0,
                    false,
                    Map.of());

            InputStream stream = ctx.rawEvidence()
                    .bufferedStream()
                    .orElseThrow(() -> new AssertionError("bufferedStream must be present"));
            byte[] readBack = stream.readAllBytes();
            assertArrayEquals(wireBytes, readBack, "bufferedStream must yield the original wire bytes");
        }

        @Test
        @DisplayName("rawEvidence() content-type is empty when null was passed at construction")
        void rawEvidenceContentTypeEmptyWhenNull() {
            byte[] wireBytes = {1};
            KafkaDispatchContext<String> ctx = new KafkaDispatchContext<>(
                    "consumer",
                    "topic",
                    0,
                    0L,
                    null,
                    "value",
                    PayloadSources.buffered(wireBytes, null),
                    Map.of(),
                    0L,
                    0,
                    false,
                    Map.of());

            assertTrue(ctx.rawEvidence().contentType().isEmpty(), "Content-type must be empty when null was passed");
        }

        @Test
        @DisplayName("rawEvidence() declaredLength equals the wire byte array length")
        void rawEvidenceDeclaredLengthMatchesArrayLength() {
            byte[] wireBytes = {1, 2, 3, 4};
            KafkaDispatchContext<String> ctx = new KafkaDispatchContext<>(
                    "consumer",
                    "topic",
                    0,
                    0L,
                    null,
                    "value",
                    PayloadSources.buffered(wireBytes, null),
                    Map.of(),
                    0L,
                    0,
                    false,
                    Map.of());

            assertEquals(4L, ctx.rawEvidence().declaredLength().orElseThrow());
        }

        @Test
        @DisplayName("rawEvidence() returns the same PayloadSource instance passed at construction")
        void rawEvidenceReturnsSameInstance() {
            byte[] wireBytes = {99};
            PayloadSource evidence = PayloadSources.buffered(wireBytes, null);
            KafkaDispatchContext<String> ctx = new KafkaDispatchContext<>(
                    "consumer", "topic", 0, 0L, null, "value", evidence, Map.of(), 0L, 0, false, Map.of());

            assertSame(evidence, ctx.rawEvidence(), "rawEvidence() must return the same PayloadSource instance");
        }
    }

    // --- Defensive copying ---

    @Nested
    @DisplayName("Defensive copying in compact constructor")
    class DefensiveCopy {

        @Test
        @DisplayName("null headers map is converted to an empty map")
        void nullHeadersProducesEmptyMap() {
            KafkaDispatchContext<String> ctx = minimalContext(null, Map.of());
            assertNotNull(ctx.headers());
            assertTrue(ctx.headers().isEmpty());
        }

        @Test
        @DisplayName("null attributes map is converted to an empty map")
        void nullAttributesProducesEmptyMap() {
            KafkaDispatchContext<String> ctx = minimalContext(Map.of(), null);
            assertNotNull(ctx.attributes());
            assertTrue(ctx.attributes().isEmpty());
        }
    }

    // --- Tombstone (null rawBytes) raw-evidence selection ---

    @Nested
    @DisplayName("Tombstone (null value) raw-evidence guard")
    class TombstoneRawEvidence {

        /**
         * Mirrors the selection in {@link dev.vertique.kafka.KafkaConsumerVerticle}: when the
         * Kafka record value is {@code null} (a tombstone/delete marker), the context must carry
         * an {@link dev.vertique.core.payload.PayloadKind#ABSENT} source so that downstream
         * capture code does not NPE on {@code declaredLength()} or {@code bufferedStream()}.
         */
        @Test
        @DisplayName("null rawBytes (tombstone) → PayloadSources.absent() yields ABSENT kind")
        void tombstoneYieldsAbsentSource() {
            byte[] rawBytes = null;
            PayloadSource evidence =
                    rawBytes == null ? PayloadSources.absent() : PayloadSources.buffered(rawBytes, null);

            assertEquals(
                    PayloadKind.ABSENT,
                    evidence.kind(),
                    "tombstone record (null value) must produce an ABSENT PayloadSource");
        }

        @Test
        @DisplayName("null rawBytes ABSENT source — declaredLength() and bufferedStream() do not throw")
        void absentSourceAccessorsDoNotNpe() {
            PayloadSource absent = PayloadSources.absent();

            // Neither accessor must throw
            absent.declaredLength();
            absent.bufferedStream();
            absent.contentType();
        }

        @Test
        @DisplayName("non-null rawBytes → PayloadSources.buffered() yields BUFFERED kind")
        void nonNullBytesYieldsBufferedSource() {
            byte[] rawBytes = new byte[] {1, 2, 3};
            PayloadSource evidence =
                    rawBytes == null ? PayloadSources.absent() : PayloadSources.buffered(rawBytes, null);

            assertEquals(
                    PayloadKind.BUFFERED,
                    evidence.kind(),
                    "non-tombstone record (non-null value) must produce a BUFFERED PayloadSource");
        }
    }
}
