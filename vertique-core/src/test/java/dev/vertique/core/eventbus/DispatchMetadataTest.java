// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DispatchMetadata} factory, accessor, and typed context lookup.
 */
class DispatchMetadataTest {

    @Test
    @DisplayName("empty() returns metadata with an empty dispatch-context map")
    void emptyMetadata() {
        DispatchMetadata md = DispatchMetadata.empty();
        assertTrue(md.dispatchContext().isEmpty());
    }

    @Test
    @DisplayName("of(empty) and of(null) return the canonical empty instance")
    void ofEmptyReturnsEmpty() {
        assertSame(DispatchMetadata.empty(), DispatchMetadata.of(Map.of()));
        assertSame(DispatchMetadata.empty(), DispatchMetadata.of(null));
    }

    @Test
    @DisplayName("of(dispatchContext) exposes a snapshot of the map")
    void ofExposesMap() {
        DispatchMetadata md = DispatchMetadata.of(Map.of("ctx", 1));
        assertEquals(1, md.dispatchContext().get("ctx"));
    }

    @Test
    @DisplayName("dispatchContext() is a snapshot copy that does not reflect later caller mutation")
    void snapshotIndependentOfCaller() {
        HashMap<String, Object> source = new HashMap<>(Map.of("ctx", 1));
        DispatchMetadata md = DispatchMetadata.of(source);
        source.put("ctx", 99);
        assertEquals(1, md.dispatchContext().get("ctx"));
    }

    @Test
    @DisplayName("context(Class) returns typed value when FQCN key matches")
    void contextLookup() {
        CorrelationContext cc = CorrelationContext.unbound();
        DispatchMetadata md = DispatchMetadata.of(Map.of(CorrelationContext.class.getName(), cc));
        assertSame(cc, md.context(CorrelationContext.class).orElseThrow());
    }

    @Test
    @DisplayName("context(Class) returns empty when key absent")
    void contextAbsent() {
        DispatchMetadata md = DispatchMetadata.empty();
        assertFalse(md.context(CorrelationContext.class).isPresent());
    }

    @Test
    @DisplayName("context(Class) returns empty when stored value is incompatible type")
    void contextWrongType() {
        DispatchMetadata md = DispatchMetadata.of(Map.of(CorrelationContext.class.getName(), "not-a-cc"));
        assertFalse(md.context(CorrelationContext.class).isPresent());
    }
}
