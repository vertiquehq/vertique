// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.security.authz.InvocationOrigin;
import java.util.Objects;

/**
 * The per-transport choices {@link IdentityPipelineFactory} assembles an identity pipeline from: the
 * {@link InvocationOrigin} the transport binds for the request lifecycle, and whether that transport
 * wires identity-snapshot capture at ingress.
 *
 * <p>{@link #rest()} and {@link #webSocket()} are the supported construction path — they are the
 * combinations the factory is contracted for. The canonical constructor is public only because a
 * record requires it; assembling an unsupported combination directly is not part of the contract.
 *
 * @param origin                  the invocation origin the assembled pipeline binds for the request
 *                                lifecycle; must not be {@code null}
 * @param identitySnapshotCapture whether the assembled pipeline wires the application-bound
 *                                {@link dev.vertique.security.runtime.IdentitySnapshotCapture};
 *                                {@code false} means no identity snapshot is captured at that
 *                                transport's ingress even when capture is bound
 */
public record IdentityPipelineOptions(InvocationOrigin origin, boolean identitySnapshotCapture) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code origin} is {@code null}
     */
    public IdentityPipelineOptions {
        Objects.requireNonNull(origin, "origin");
    }

    /**
     * REST defaults: {@link IdentityResolutionMiddleware#REST_ORIGIN} as the invocation origin, with
     * identity-snapshot capture wired whenever the application binds it.
     *
     * @return the REST assembly options; never {@code null}
     */
    public static IdentityPipelineOptions rest() {
        return new IdentityPipelineOptions(IdentityResolutionMiddleware.REST_ORIGIN, true);
    }

    /**
     * WebSocket defaults: an {@link InvocationOrigin} of kind {@link DispatchBoundary#WEBSOCKET},
     * with identity-snapshot capture disabled — a channel upgrade establishes a long-lived identity
     * whose snapshot semantics are not the per-request ones capture is defined for, so no snapshot is
     * captured at upgrade.
     *
     * @return the WebSocket assembly options; never {@code null}
     */
    public static IdentityPipelineOptions webSocket() {
        return new IdentityPipelineOptions(InvocationOrigin.of(DispatchBoundary.WEBSOCKET), false);
    }
}
