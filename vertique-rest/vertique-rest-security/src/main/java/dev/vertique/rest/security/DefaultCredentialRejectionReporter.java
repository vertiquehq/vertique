// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.rest.core.security.DeferredCredentialRejectionAuthHandler;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.VerificationSource;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link CredentialRejectionReporter} implementation.
 *
 * <p>On each {@link #report} call, assembles a {@link CredentialRejectedEvent} from:
 * <ul>
 *   <li>The supplied method, credential id, verification source, reason code, and safe
 *       attributes.</li>
 *   <li>The pre-auth-bound {@link RequestOrigin} stashed on the routing context under
 *       {@code RequestOrigin.class.getName()} by
 *       {@link OriginCaptureMiddleware} (absent if origin capture is not configured).</li>
 *   <li>The live {@link CorrelationContext} bound on the {@link ContextHolder} by
 *       {@link dev.vertique.rest.core.correlation.CorrelationIngressMiddleware} before any auth
 *       handler runs.</li>
 * </ul>
 *
 * <p><strong>Single-scheme route (default).</strong> The assembled event is emitted immediately
 * through {@link SecurityEventEmitter}. Emission must happen here rather than in a downstream
 * middleware because credential rejection is always followed by {@code ctx.fail(...)}, which
 * short-circuits the route handler chain — any middleware scheduled after the failing auth handler
 * (including identity resolution) never runs.
 *
 * <p><strong>OR route (deferred).</strong> When the request was mounted behind a
 * {@link DeferredCredentialRejectionAuthHandler} (an OR route — multiple alternative
 * {@code @SecurityRequirement}s composed as a {@code ChainAuthHandler.any()}), the wrapper has set
 * {@link DeferredCredentialRejectionAuthHandler#DEFER_CONTEXT_KEY} on the routing context. In that
 * case the assembled event is <em>buffered</em> on the routing context instead of emitted
 * immediately: an earlier alternative that rejects the credential must not record a rejection when a
 * <em>later</em> alternative ultimately authenticates. The buffered rejection(s) are flushed from a
 * single {@link RoutingContext#addEndHandler} registered on first buffering, and only when
 * authentication ultimately failed ({@code ctx.user() == null} at response end — no alternative
 * authenticated). The event is still <em>assembled</em> eagerly so the live {@link RequestOrigin} and
 * {@link CorrelationContext} are captured while still bound, not at response-end time.
 */
@Singleton
public final class DefaultCredentialRejectionReporter implements CredentialRejectionReporter {

    /**
     * Routing-context key under which the per-request buffer of deferred {@link CredentialRejectedEvent}s
     * is stored on the OR-route path. Private to the reporter — the buffer is an implementation detail
     * of the deferral mechanism.
     */
    private static final String DEFERRED_BUFFER_KEY =
            DefaultCredentialRejectionReporter.class.getName() + ".deferredRejections";

    private final ContextHolder holder;
    private final SecurityEventEmitter emitter;

    /**
     * Creates a new {@code DefaultCredentialRejectionReporter}.
     *
     * @param holder  the context holder used to read the bound {@link CorrelationContext}; must not
     *                be {@code null}
     * @param emitter the security event emitter that fans the rejection out to observers; must not
     *                be {@code null}
     */
    @Inject
    public DefaultCredentialRejectionReporter(ContextHolder holder, SecurityEventEmitter emitter) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    // --- CredentialRejectionReporter ---

    /**
     * {@inheritDoc}
     *
     * <p>Reads the {@link CorrelationContext} from the context holder and the
     * {@link RequestOrigin} from the routing context, assembles a {@link CredentialRejectedEvent},
     * then either emits it immediately (single-scheme route) or buffers it for flush at response end
     * (OR route — see the class javadoc). The event is always assembled eagerly so correlation and
     * origin are captured while still bound.
     *
     * @throws IllegalStateException if {@link CorrelationContext} is not bound on the context
     *                               holder — guaranteed not to throw when
     *                               {@link OriginCaptureMiddleware} runs after
     *                               {@link dev.vertique.rest.core.correlation.CorrelationIngressMiddleware}
     *                               (which is the normal request flow)
     */
    @Override
    public void report(
            RoutingContext ctx,
            AuthMethod attemptedMethod,
            Optional<String> credentialId,
            Optional<VerificationSource> verificationSource,
            String reasonCode,
            Map<String, Object> safeAttributes) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(attemptedMethod, "attemptedMethod");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(verificationSource, "verificationSource");
        Objects.requireNonNull(reasonCode, "reasonCode");

        // CorrelationContext is mandatory — bound by CorrelationIngressMiddleware before any
        // auth handler executes. Throw immediately if it is absent rather than assembling a
        // structurally invalid event.
        CorrelationContext correlation = holder.current(CorrelationContext.class)
                .orElseThrow(() -> new IllegalStateException(
                        "CorrelationContext must be bound before CredentialRejectionReporter.report() is called"));

        // RequestOrigin is optional — OriginCaptureMiddleware stashes it under the well-known
        // key RequestOrigin.class.getName(). Absent when origin capture is not configured.
        Optional<RequestOrigin> origin = Optional.ofNullable(ctx.get(RequestOrigin.class.getName()));

        CredentialRejectedEvent event = new CredentialRejectedEvent(
                Instant.now(),
                correlation,
                origin,
                attemptedMethod,
                credentialId,
                verificationSource,
                reasonCode,
                safeAttributes == null ? Map.of() : safeAttributes);

        // Defer ONLY rejections raised during the OR chain's authentication attempts: the deferral
        // flag is armed AND no alternative has authenticated yet (ctx.user() == null). A
        // post-authentication rejection on an OR route — e.g. a claims validator failing a request
        // that a later alternative already authenticated — has ctx.user() != null here and must emit
        // immediately: it is a real 401, not a superseded chain alternative. The flag is never
        // cleared, so this user-null guard (not the flag alone) is what scopes deferral to the chain
        // attempts. (#154 review finding.)
        if (Boolean.TRUE.equals(ctx.get(DeferredCredentialRejectionAuthHandler.DEFER_CONTEXT_KEY))
                && ctx.user() == null) {
            bufferForDeferredEmit(ctx, event);
        } else {
            emitter.emit(event);
        }
    }

    /**
     * Buffers a {@link CredentialRejectedEvent} for deferred emission on the OR-route path. On first
     * buffering for a request, creates the buffer and registers a single
     * {@link RoutingContext#addEndHandler} that flushes the buffer — but only when authentication
     * ultimately failed ({@code ctx.user() == null} at response end, i.e. no OR alternative
     * authenticated). When a later alternative authenticated, the buffered rejections are silently
     * dropped, so a successful OR request records no spurious rejection.
     *
     * @param ctx   the routing context, carrying the deferral flag set by
     *              {@link DeferredCredentialRejectionAuthHandler}
     * @param event the assembled rejection event to buffer
     */
    private void bufferForDeferredEmit(RoutingContext ctx, CredentialRejectedEvent event) {
        // Single-threaded by construction: every report() for a given request runs on that request's
        // Vert.x event-loop thread (scheme handlers report synchronously), so the check-then-create of
        // the buffer and the plain ArrayList need no synchronization. A future worker-thread rejection
        // path would have to revisit this.
        List<CredentialRejectedEvent> buffer = ctx.get(DEFERRED_BUFFER_KEY);
        if (buffer == null) {
            buffer = new ArrayList<>();
            ctx.put(DEFERRED_BUFFER_KEY, buffer);
            List<CredentialRejectedEvent> pending = buffer;
            ctx.addEndHandler(ar -> {
                // Flush only when authentication ultimately failed — no OR alternative set a user.
                // A later alternative that authenticated leaves ctx.user() non-null, so the earlier
                // alternatives' buffered rejections are dropped.
                if (ctx.user() == null) {
                    pending.forEach(emitter::emit);
                }
            });
        }
        buffer.add(event);
    }
}
