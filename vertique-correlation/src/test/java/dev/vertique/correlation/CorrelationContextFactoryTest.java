// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdGenerator;
import dev.vertique.core.correlation.CorrelationIdentifier;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationContextFactory}.
 *
 * <p>Verifies the three creation paths (create / seed / fromSnapshot) plus the optional
 * generator override contract.
 */
class CorrelationContextFactoryTest {

    private static final CorrelationIdentifier REQ_ID = new CorrelationIdentifier("req-1", "test");
    private static final CorrelationIdentifier CORR_ID = new CorrelationIdentifier("corr-1", "test");

    @Test
    @DisplayName("create() returns a fresh CorrelationContext bound to the given ids")
    void createBindsGivenIds() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContext ctx = factory.create(REQ_ID, CORR_ID);

        assertEquals(REQ_ID, ctx.requestId());
        assertEquals(CORR_ID, ctx.correlationId());
    }

    @Test
    @DisplayName("create() rejects null requestId / correlationId")
    void createRejectsNulls() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        assertThrows(NullPointerException.class, () -> factory.create(null, CORR_ID));
        assertThrows(NullPointerException.class, () -> factory.create(REQ_ID, null));
    }

    @Test
    @DisplayName("seed() mints both ids and tags source as 'seeded:<boundary>'")
    void seedTagsSourceWithBoundary() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContext ctx = factory.seed("kafka");

        assertEquals("seeded:kafka", ctx.requestId().source());
        assertEquals("seeded:kafka", ctx.correlationId().source());
        assertNotEquals(ctx.requestId().value(), ctx.correlationId().value(), "ids should be independently minted");
    }

    @Test
    @DisplayName("seed() rejects null boundary")
    void seedRejectsNullBoundary() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        assertThrows(NullPointerException.class, () -> factory.seed(null));
    }

    @Test
    @DisplayName("fromSnapshot() rebuilds a live context independent of the snapshot")
    void fromSnapshotRebuildsLive() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContextSnapshot snap = CorrelationContextSnapshot.of(REQ_ID, CORR_ID);

        CorrelationContext ctx = factory.fromSnapshot(snap);

        assertNotSame(snap, ctx);
        assertEquals(REQ_ID, ctx.requestId());
        assertEquals(CORR_ID, ctx.correlationId());
        // Snapshot from rebuilt context equals the input snapshot.
        assertEquals(snap, ctx.snapshot());
    }

    @Test
    @DisplayName("default generator is Uuid4CorrelationIdGenerator.INSTANCE when no override is supplied")
    void defaultGeneratorIsUuid4() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        assertSame(Uuid4CorrelationIdGenerator.INSTANCE, factory.generator());
    }

    @Test
    @DisplayName("application-supplied generator wins over the default")
    void overrideGeneratorWins() {
        CorrelationIdGenerator fixed = () -> "fixed-id";
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.of(fixed));

        assertSame(fixed, factory.generator());
        CorrelationContext ctx = factory.seed("test");
        assertEquals("fixed-id", ctx.requestId().value());
        assertEquals("fixed-id", ctx.correlationId().value());
    }

    @Test
    @DisplayName("seed() ids are RFC 4122 v4 by default")
    void seedProducesUuidV4ByDefault() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContext ctx = factory.seed("test");

        assertTrue(java.util.UUID.fromString(ctx.requestId().value()).version() == 4);
        assertTrue(java.util.UUID.fromString(ctx.correlationId().value()).version() == 4);
    }
}
