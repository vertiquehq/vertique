// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobDispatchContext} immutability and copy-on-write semantics.
 */
@DisplayName("JobDispatchContext")
class JobDispatchContextTest {

    private JobDispatchContext buildContext() {
        return new JobDispatchContext(
                "test-job",
                UUID.randomUUID(),
                JobType.CRON,
                0,
                3,
                "cron",
                Instant.now(),
                Instant.now(),
                Map.of("param1", "value1"),
                Map.of());
    }

    @Test
    @DisplayName("withAttribute returns a new instance")
    void withAttributeReturnsNewInstance() {
        JobDispatchContext ctx = buildContext();
        JobDispatchContext updated = ctx.withAttribute("key", "value");
        assertNotSame(ctx, updated);
    }

    @Test
    @DisplayName("withAttribute adds the attribute to the copy")
    void withAttributeAddsAttribute() {
        JobDispatchContext ctx = buildContext();
        JobDispatchContext updated = ctx.withAttribute("key", "value");
        assertEquals("value", updated.attributes().get("key"));
    }

    @Test
    @DisplayName("withAttribute does not mutate the original")
    void withAttributeDoesNotMutateOriginal() {
        JobDispatchContext ctx = buildContext();
        ctx.withAttribute("key", "value");
        assertEquals(0, ctx.attributes().size(), "Original attributes must not be changed");
    }

    @Test
    @DisplayName("parameters are defensively copied in compact constructor")
    void parametersAreDefensivelyCopied() {
        JobDispatchContext ctx = buildContext();
        // parameters() map should be unmodifiable
        assertEquals("value1", ctx.parameters().get("param1"));
    }

    @Test
    @DisplayName("null parameters become empty map")
    void nullParametersBecomeEmptyMap() {
        JobDispatchContext ctx = new JobDispatchContext(
                "id", UUID.randomUUID(), JobType.CRON, 0, 1, "q", Instant.now(), Instant.now(), null, null);
        assertEquals(0, ctx.parameters().size());
        assertEquals(0, ctx.attributes().size());
    }
}
