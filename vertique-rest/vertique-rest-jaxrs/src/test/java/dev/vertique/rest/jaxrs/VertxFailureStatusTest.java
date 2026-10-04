// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VertxFailureStatus}'s double-fail observation: {@code fail(int)} rewriting
 * {@code statusCode} without clearing {@code failure} must not stash the rewritten 4xx over the
 * status that accompanied the cause.
 */
class VertxFailureStatusTest {

    private RoutingContext ctx;
    private Map<String, Object> data;

    @BeforeEach
    void setUp() {
        ctx = mock(RoutingContext.class);
        data = new HashMap<>();
        when(ctx.data()).thenReturn(data);
    }

    @Test
    @DisplayName("A single fail(4xx, cause) exposes that 4xx as the client-error status to stash")
    void singleClientErrorFailExposesStatus() {
        RuntimeException cause = new RuntimeException("auth");
        when(ctx.failure()).thenReturn(cause);
        when(ctx.statusCode()).thenReturn(401);

        VertxFailureStatus.observeFailurePair(ctx);

        assertEquals(401, VertxFailureStatus.clientErrorStatusForCause(ctx));
    }

    @Test
    @DisplayName("fail(500, cause) then fail(404) keeps the original 500 — no 4xx stash")
    void statusOnlyRewriteAfterServerErrorDoesNotStashFourHundred() {
        RuntimeException serverError = new RuntimeException("server");
        when(ctx.failure()).thenReturn(serverError);
        when(ctx.statusCode()).thenReturn(500);
        VertxFailureStatus.observeFailurePair(ctx);

        when(ctx.statusCode()).thenReturn(404);
        VertxFailureStatus.observeFailurePair(ctx);

        assertNull(
                VertxFailureStatus.clientErrorStatusForCause(ctx),
                "a status-only rewrite must not turn a server-error cause into a stashed 404");
        assertEquals(500, data.get(VertxFailureStatus.OBSERVED_STATUS_KEY));
        assertEquals(serverError, data.get(VertxFailureStatus.OBSERVED_FAILURE_KEY));
    }

    @Test
    @DisplayName("fail(401, cause) then fail(404) keeps the original 401 for the stash")
    void statusOnlyRewriteAfterClientErrorKeepsOriginalFourHundred() {
        RuntimeException cause = new RuntimeException("auth");
        when(ctx.failure()).thenReturn(cause);
        when(ctx.statusCode()).thenReturn(401);
        VertxFailureStatus.observeFailurePair(ctx);

        when(ctx.statusCode()).thenReturn(404);
        VertxFailureStatus.observeFailurePair(ctx);

        assertEquals(
                401,
                VertxFailureStatus.clientErrorStatusForCause(ctx),
                "the status that accompanied the cause must win over a later fail(int)");
    }

    @Test
    @DisplayName("A new failure reference refreshes the observed pair")
    void newFailureReferenceRefreshesPair() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        when(ctx.failure()).thenReturn(first);
        when(ctx.statusCode()).thenReturn(500);
        VertxFailureStatus.observeFailurePair(ctx);

        when(ctx.failure()).thenReturn(second);
        when(ctx.statusCode()).thenReturn(401);
        VertxFailureStatus.observeFailurePair(ctx);

        assertEquals(401, VertxFailureStatus.clientErrorStatusForCause(ctx));
        assertEquals(second, data.get(VertxFailureStatus.OBSERVED_FAILURE_KEY));
    }
}
