// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationContextValueAdapter}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link CorrelationContextValueAdapter#type()} returns the public interface — required
 *       so the substrate's FQCN-keyed adapter lookup matches the holder bind key.</li>
 *   <li>{@link CorrelationContextValueAdapter#snapshot} returns the live context's snapshot.</li>
 *   <li>{@link CorrelationContextValueAdapter#restoreFromSnapshot} materialises a fresh
 *       independent context that round-trips through {@link CorrelationContext#snapshot}.</li>
 *   <li>{@link CorrelationContextValueAdapter#duplicate} returns a fresh independent context.</li>
 *   <li>The adapter is discoverable through Java {@link ServiceLoader} (the substrate uses this
 *       to find adapters at bootstrap before Dagger exists).</li>
 *   <li>Bad inputs raise {@link IllegalArgumentException} / {@link NullPointerException}.</li>
 * </ul>
 */
class CorrelationContextValueAdapterTest {

    private static final CorrelationIdentifier REQ_ID = new CorrelationIdentifier("req-1", "test");
    private static final CorrelationIdentifier CORR_ID = new CorrelationIdentifier("corr-1", "test");

    @Test
    @DisplayName("type() returns CorrelationContext.class — the public interface (not the impl)")
    void typeIsThePublicInterface() {
        CorrelationContextValueAdapter adapter = new CorrelationContextValueAdapter();
        assertSame(CorrelationContext.class, adapter.type());
    }

    @Test
    @DisplayName("snapshot(live) returns the live context's snapshot")
    void snapshotReturnsLiveSnapshot() {
        CorrelationContextValueAdapter adapter = new CorrelationContextValueAdapter();
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.putAttribute("key", "value");

        Object snap = adapter.snapshot(live);
        assertInstanceOf(CorrelationContextSnapshot.class, snap);
        assertEquals(live.snapshot(), snap);
    }

    @Test
    @DisplayName("snapshot(null) raises NullPointerException")
    void snapshotNullThrows() {
        CorrelationContextValueAdapter adapter = new CorrelationContextValueAdapter();
        assertThrows(NullPointerException.class, () -> adapter.snapshot(null));
    }

    @Test
    @DisplayName("restoreFromSnapshot rebuilds a fresh live context equal-by-fields to the input")
    void restoreFromSnapshotRebuildsLive() {
        CorrelationContextValueAdapter adapter = new CorrelationContextValueAdapter();
        CorrelationContextSnapshot snap = CorrelationContextSnapshot.of(REQ_ID, CORR_ID);

        CorrelationContext restored = adapter.restoreFromSnapshot(snap);
        assertNotSame(snap, restored);
        assertEquals(snap, restored.snapshot());
    }

    @Test
    @DisplayName("restoreFromSnapshot(null) raises NullPointerException")
    void restoreNullThrows() {
        CorrelationContextValueAdapter adapter = new CorrelationContextValueAdapter();
        assertThrows(NullPointerException.class, () -> adapter.restoreFromSnapshot(null));
    }

    @Test
    @DisplayName("restoreFromSnapshot with wrong type raises IllegalArgumentException naming the unexpected class")
    void restoreWrongTypeThrows() {
        CorrelationContextValueAdapter adapter = new CorrelationContextValueAdapter();
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> adapter.restoreFromSnapshot("not-a-snapshot"));
        assertTrue(ex.getMessage().contains(String.class.getName()), "message must name the unexpected class");
    }

    @Test
    @DisplayName("duplicate(live) returns a fresh independently mutable context")
    void duplicateReturnsIndependentContext() {
        CorrelationContextValueAdapter adapter = new CorrelationContextValueAdapter();
        MutableCorrelationContext source = new MutableCorrelationContext(REQ_ID, CORR_ID);
        source.putAttribute("k", "v");

        CorrelationContext dup = adapter.duplicate(source);
        assertNotSame(source, dup);
        assertEquals("v", dup.attributes().get("k"));

        // Mutate source after duplicate — duplicate must not see it.
        source.putAttribute("k", "v2");
        assertEquals("v", dup.attributes().get("k"));
    }

    @Test
    @DisplayName("ServiceLoader discovers CorrelationContextValueAdapter from this module's META-INF")
    void serviceLoaderDiscoversAdapter() {
        boolean found = ServiceLoader.load(
                        dev.vertique.core.context.ContextValueAdapter.class,
                        CorrelationContextValueAdapter.class.getClassLoader())
                .stream()
                .map(p -> p.type().getName())
                .collect(Collectors.toSet())
                .contains(CorrelationContextValueAdapter.class.getName());
        assertTrue(found, "ServiceLoader must surface CorrelationContextValueAdapter via META-INF/services");
    }
}
