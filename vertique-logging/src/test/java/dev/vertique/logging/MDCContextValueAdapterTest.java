// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MDCContextValueAdapter}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link MDCContextValueAdapter#type()} returns {@link MDCContext}{@code .class}.
 *   <li>{@link MDCContextValueAdapter#snapshot(MDCContext)} returns an immutable
 *       {@code Map<String, String>} matching the live entries.
 *   <li>{@link MDCContextValueAdapter#restoreFromSnapshot(Object)} returns a fresh
 *       {@link MDCContext} with the same entries when given a valid {@code Map<String, String>}.
 *   <li>{@link MDCContextValueAdapter#restoreFromSnapshot(Object)} throws
 *       {@link IllegalArgumentException} for a non-{@code Map} argument.
 *   <li>{@link MDCContextValueAdapter#duplicate(MDCContext)} returns a fresh independent instance
 *       — mutating the duplicate does NOT affect the original.
 *   <li>Null arguments to {@code snapshot}, {@code restoreFromSnapshot}, and {@code duplicate}
 *       throw {@link NullPointerException}.
 * </ul>
 */
class MDCContextValueAdapterTest {

    private final MDCContextValueAdapter adapter = new MDCContextValueAdapter();

    // --- type() ---

    @Test
    @DisplayName("type() returns MDCContext.class")
    void typeReturnsMdcContextClass() {
        assertEquals(MDCContext.class, adapter.type());
    }

    // --- snapshot() ---

    @Test
    @DisplayName("snapshot(live) returns an immutable Map<String,String> matching the live entries")
    void snapshotReturnsImmutableMapMatchingLiveEntries() {
        MDCContext live = new MDCContext(Map.of("requestId", "r1", "userId", "u1"));

        Object snap = adapter.snapshot(live);

        assertNotNull(snap, "snapshot must not be null");
        assertEquals(Map.of("requestId", "r1", "userId", "u1"), snap);
        // Must be immutable — any mutation attempt must throw.
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Object, Object> snapMap = (Map<Object, Object>) (Map) snap;
        assertThrows(UnsupportedOperationException.class, () -> snapMap.put("extra", "v"));
    }

    @Test
    @DisplayName("snapshot(live) returns an empty map when the live context is empty")
    void snapshotOfEmptyContextReturnsEmptyMap() {
        MDCContext live = new MDCContext();
        Object snap = adapter.snapshot(live);
        assertEquals(Map.of(), snap);
    }

    @Test
    @DisplayName("snapshot(null) throws NullPointerException")
    void snapshotNullThrowsNpe() {
        assertThrows(NullPointerException.class, () -> adapter.snapshot(null));
    }

    // --- restoreFromSnapshot() ---

    @Test
    @DisplayName("restoreFromSnapshot(Map) returns a fresh MDCContext with the same entries")
    void restoreFromSnapshotReturnsNewMdcContextWithSameEntries() {
        Map<String, String> frozen = Map.of("k", "v");

        MDCContext restored = adapter.restoreFromSnapshot(frozen);

        assertNotNull(restored, "restored must not be null");
        assertEquals("v", restored.get("k"));
    }

    @Test
    @DisplayName("restoreFromSnapshot(non-Map) throws IllegalArgumentException")
    void restoreFromSnapshotWithNonMapThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> adapter.restoreFromSnapshot("not-a-map"));
    }

    @Test
    @DisplayName("restoreFromSnapshot(null) throws NullPointerException")
    void restoreFromSnapshotNullThrowsNpe() {
        assertThrows(NullPointerException.class, () -> adapter.restoreFromSnapshot(null));
    }

    @Test
    @DisplayName("restoreFromSnapshot produces a value independent of the snapshot map")
    void restoreFromSnapshotIsIndependentOfSnapshot() {
        // Snapshot is immutable (Map.of), so we just verify the restored context is a distinct object.
        Map<String, String> frozen = Map.of("k", "original");
        MDCContext restored = adapter.restoreFromSnapshot(frozen);

        // Mutating the restored context must not affect the snapshot (it's a new HashMap internally).
        restored.put("k", "mutated");
        assertEquals(
                "original",
                frozen.get("k"),
                "frozen snapshot must not be affected by mutation of the restored context");
    }

    // --- duplicate() ---

    @Test
    @DisplayName("duplicate(live) returns a distinct MDCContext instance with the same entries")
    void duplicateReturnsDistinctInstanceWithSameEntries() {
        MDCContext live = new MDCContext(Map.of("requestId", "r1"));

        MDCContext dup = adapter.duplicate(live);

        assertNotSame(live, dup, "duplicate must be a different object");
        assertEquals("r1", dup.get("requestId"));
    }

    @Test
    @DisplayName("mutating the duplicate does not affect the original")
    void mutatingDuplicateDoesNotAffectOriginal() {
        MDCContext live = new MDCContext(Map.of("requestId", "r1"));
        MDCContext dup = adapter.duplicate(live);

        dup.put("requestId", "dup-r2");
        dup.put("newKey", "dup-only");

        assertEquals("r1", live.get("requestId"), "original must not see duplicate's mutation");
        assertEquals(null, live.get("newKey"), "original must not see keys added to the duplicate");
    }

    @Test
    @DisplayName("mutating the original does not affect the duplicate")
    void mutatingOriginalDoesNotAffectDuplicate() {
        MDCContext live = new MDCContext(Map.of("k", "v1"));
        MDCContext dup = adapter.duplicate(live);

        live.put("k", "v2");

        assertEquals("v1", dup.get("k"), "duplicate must not see mutation on the original");
    }

    @Test
    @DisplayName("duplicate(null) throws NullPointerException")
    void duplicateNullThrowsNpe() {
        assertThrows(NullPointerException.class, () -> adapter.duplicate(null));
    }
}
