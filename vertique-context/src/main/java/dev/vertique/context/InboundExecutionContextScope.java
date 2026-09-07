// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.InboundContextInitializationContext;
import dev.vertique.core.context.InboundContextInitializer;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * Dagger {@link Singleton} lifecycle helper that orchestrates first-ingress context installation
 * by combining inbound dispatch/durable binding with registered
 * {@link InboundContextInitializer} calls.
 *
 * <p>Two installation entry points are provided:
 * <ul>
 *   <li>{@link #installDispatch(Map, String)} — for service-dispatch boundaries; uses
 *       {@link InboundDispatchScope#install(Map)}.
 *   <li>{@link #installDurable(DurableMetadata, String)} — for durable boundaries (Kafka, outbox);
 *       uses {@link DurableContextPropagator#bindFrom(DurableMetadata, String)}.
 * </ul>
 *
 * <p>Both methods open the inbound scope first, then invoke each registered initializer in
 * iteration order. If any initializer throws, all previously-opened scopes (initializer scopes in
 * LIFO order, then the inbound scope) are closed before the exception propagates, ensuring no
 * partial state leaks into the calling thread.
 *
 * <p>The returned composite scope closes in LIFO order: initializer scopes first (reverse
 * iteration), then the inbound scope last.
 */
@Singleton
public final class InboundExecutionContextScope {

    private final InboundDispatchScope inboundDispatchScope;
    private final DurableContextPropagator durableContextPropagator;
    private final Set<InboundContextInitializer> initializers;

    /**
     * Constructs the scope orchestrator.
     *
     * @param inboundDispatchScope       the inbound dispatch scope for service-dispatch boundaries;
     *                                   must not be {@code null}
     * @param durableContextPropagator   the durable context propagator for durable boundaries; must
     *                                   not be {@code null}
     * @param initializers               the set of registered first-ingress initializers; must not
     *                                   be {@code null}
     */
    @Inject
    public InboundExecutionContextScope(
            InboundDispatchScope inboundDispatchScope,
            DurableContextPropagator durableContextPropagator,
            Set<InboundContextInitializer> initializers) {
        this.inboundDispatchScope =
                Objects.requireNonNull(inboundDispatchScope, "inboundDispatchScope must not be null");
        this.durableContextPropagator =
                Objects.requireNonNull(durableContextPropagator, "durableContextPropagator must not be null");
        this.initializers = Objects.requireNonNull(initializers, "initializers must not be null");
    }

    /**
     * Installs decoded dispatch context from a service-dispatch boundary, then runs all registered
     * initializers, and returns one composed scope.
     *
     * <p>If any initializer throws after the inbound scope opened, the already-collected
     * initializer scopes are closed in LIFO order, the inbound scope is closed, and the exception
     * is rethrown (with any close-time exceptions attached via
     * {@link Throwable#addSuppressed(Throwable)}).
     *
     * @param dispatchContext the FQCN-keyed dispatch context map from the inbound envelope; may be
     *                        {@code null} or empty
     * @param boundary        the boundary identifier (e.g., {@code "service-dispatch"})
     * @return a composite scope that unwinds all installations in LIFO order on close; never
     *         {@code null}
     */
    public ContextHolder.Scope installDispatch(Map<String, Object> dispatchContext, String boundary) {
        ContextHolder.Scope inboundScope = inboundDispatchScope.install(dispatchContext);
        return installInitializers(inboundScope, boundary);
    }

    /**
     * Binds durable metadata via {@link DurableContextPropagator#bindFrom(DurableMetadata, String)},
     * then runs all registered initializers, and returns one composed scope.
     *
     * <p>If the propagator returns a no-op scope (e.g., empty metadata outside a Vert.x context
     * in unit tests), the initializers still run — the no-op is only for the bind side.
     *
     * <p>If any initializer throws after the durable scope opened, the already-collected
     * initializer scopes are closed in LIFO order, the durable scope is closed, and the exception
     * is rethrown (with any close-time exceptions attached via
     * {@link Throwable#addSuppressed(Throwable)}).
     *
     * @param metadata the full durable metadata document from the boundary's native carrier; must
     *                 not be {@code null}
     * @param boundary the durable boundary identifier (e.g., {@code "kafka"}, {@code "outbox"})
     * @return a composite scope that unwinds all installations in LIFO order on close; never
     *         {@code null}
     */
    public ContextHolder.Scope installDurable(DurableMetadata metadata, String boundary) {
        ContextHolder.Scope durableScope = durableContextPropagator.bindFrom(metadata, boundary);
        return installInitializers(durableScope, boundary);
    }

    /**
     * Carrier-aware {@link #installDurable(DurableMetadata, String)}: identical installation
     * semantics, but threads the supplied durable row-carrier binding into
     * {@link DurableContextPropagator#bindFrom(DurableMetadata, String, DurableCarrierDescriptor)}
     * so a boundary that owns a persisted row (e.g. a workflow-timer row being re-enqueued by
     * recovery) can verify the decoded durable envelope was signed for that exact row before
     * installing it (F5 row binding, PRD identity-002).
     *
     * @param metadata the full durable metadata document from the boundary's native carrier; must
     *                 not be {@code null}
     * @param boundary the durable boundary identifier (e.g., {@code "kafka"}, {@code "outbox"})
     * @param carrier  the durable row-carrier binding to verify the decoded envelope against; must
     *                 not be {@code null}
     * @return a composite scope that unwinds all installations in LIFO order on close; never
     *         {@code null}
     */
    public ContextHolder.Scope installDurable(
            DurableMetadata metadata, String boundary, DurableCarrierDescriptor carrier) {
        ContextHolder.Scope durableScope = durableContextPropagator.bindFrom(metadata, boundary, carrier);
        return installInitializers(durableScope, boundary);
    }

    /**
     * Installs the durable scope for {@code metadata}/{@code boundary} via {@link #installDurable},
     * runs {@code action} inside it, and closes the scope once {@code action}'s returned
     * {@link Future} completes — on both success and failure.
     *
     * <p>This is the shared shape duplicated at three call sites before its extraction
     * ({@code WorkflowContextBinder.withBound}, {@code BranchTransitionEngine.driveBranchTransitions},
     * {@code PgWorkflowBranchRecoveryService.withBranchDurableBound}): install, invoke {@code action}
     * inside the same {@code try} block that performed the install, and close on
     * {@link Future#eventually(Supplier)} so the binding survives the async chain. {@code action} is
     * called synchronously, inside the {@code try} — a synchronous {@link RuntimeException} thrown by
     * {@code action} itself (as opposed to a returned failed {@link Future}) is caught here, the
     * scope is closed, and the exception is surfaced as a failed {@link Future}, never rethrown (the
     * CORE-001 sync-throw lesson: an uncaught synchronous throw would leak the installed durable
     * binding into the caller's context).
     *
     * <p>Only {@link RuntimeException} is caught here — matching the posture of all three call sites
     * this method replaces ({@code WorkflowContextBinder.withBound},
     * {@code BranchTransitionEngine.driveBranchTransitions},
     * {@code PgWorkflowBranchRecoveryService.withBranchDurableBound}), each of which catches only
     * {@code RuntimeException} around its drive/action call. An {@link Error} is deliberately not
     * caught: on an {@code Error} the scope is left open and the throwable propagates unconverted,
     * on the assumption that the JVM may be in an unrecoverable state where attempting further work
     * (including a clean scope close) is not safe to rely on. This differs from
     * {@link #installInitializers}, which catches {@code RuntimeException | Error} for its own
     * narrower initializer-cleanup concern — that broader catch is not part of the contract being
     * unified here.
     *
     * @param metadata the durable metadata document to install for {@code action}'s lifetime; must
     *                 not be {@code null}
     * @param boundary the durable boundary identifier (e.g. {@code "workflow"}); must not be
     *                 {@code null}
     * @param action   the action to run inside the installed scope; must not be {@code null}
     * @param <T>      the action's result type
     * @return a {@link Future} completing with {@code action}'s result; fails with the cause of a
     *     failed {@link Future} returned by {@code action}, or with a {@link RuntimeException}
     *     {@code action} threw synchronously
     */
    public <T> Future<T> installDurableAndRun(DurableMetadata metadata, String boundary, Supplier<Future<T>> action) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(boundary, "boundary must not be null");
        Objects.requireNonNull(action, "action must not be null");

        ContextHolder.Scope scope = null;
        try {
            scope = installDurable(metadata, boundary);
            Future<T> actionFuture = action.get();
            ContextHolder.Scope finalScope = scope;
            return actionFuture.eventually(() -> {
                finalScope.close();
                return Future.succeededFuture();
            });
        } catch (RuntimeException e) {
            if (scope != null) {
                scope.close();
            }
            return Future.failedFuture(e);
        }
    }

    // --- Internal helpers ---

    /**
     * Runs all registered initializers after the given inbound scope has been opened. If any
     * initializer throws, previously opened scopes are cleaned up before propagating.
     *
     * @param inboundScope the scope opened by the inbound install step (dispatch or durable)
     * @param boundary     the boundary identifier passed to each initializer's context
     * @return the composed scope; never {@code null}
     */
    private ContextHolder.Scope installInitializers(ContextHolder.Scope inboundScope, String boundary) {
        if (initializers.isEmpty()) {
            return inboundScope;
        }
        InboundContextInitializationContext initCtx = new InboundContextInitializationContext(boundary);
        List<ContextHolder.Scope> initScopes = new ArrayList<>(initializers.size());
        try {
            for (InboundContextInitializer initializer : initializers) {
                initScopes.add(initializer.initialize(initCtx));
            }
        } catch (RuntimeException | Error ex) {
            // Clean up initializer scopes opened so far (LIFO), then the inbound scope
            closeCollecting(initScopes, ex);
            closeCollecting(inboundScope, ex);
            throw ex;
        }
        // Build the composite scope: inbound first (closes last in LIFO), then initializer scopes
        ContextHolder.Scope[] allScopes = new ContextHolder.Scope[1 + initScopes.size()];
        allScopes[0] = inboundScope;
        for (int i = 0; i < initScopes.size(); i++) {
            allScopes[1 + i] = initScopes.get(i);
        }
        return CompositeContextScope.of(allScopes);
    }

    /**
     * Closes all scopes in {@code scopes} in LIFO order, collecting any close-time exceptions as
     * suppressed on {@code primary}.
     *
     * @param scopes  the list of scopes to close in reverse order
     * @param primary the primary exception to which suppressed exceptions are attached
     */
    private static void closeCollecting(List<ContextHolder.Scope> scopes, Throwable primary) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            try {
                scopes.get(i).close();
            } catch (RuntimeException closeEx) {
                primary.addSuppressed(closeEx);
            }
        }
    }

    /**
     * Closes a single scope, collecting any close-time exception as suppressed on {@code primary}.
     *
     * @param scope   the scope to close; must not be {@code null}
     * @param primary the primary exception to which a suppressed exception is attached on failure
     */
    private static void closeCollecting(ContextHolder.Scope scope, Throwable primary) {
        try {
            scope.close();
        } catch (RuntimeException closeEx) {
            primary.addSuppressed(closeEx);
        }
    }
}
