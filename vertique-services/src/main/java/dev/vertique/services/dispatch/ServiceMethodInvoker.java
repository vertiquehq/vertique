// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.dispatch;

import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.async.Combinators;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Event bus consumer handler for a single service operation.
 *
 * <p>Implements the full dispatch flow for one operation:
 * <ol>
 *   <li>Decode and install the dispatch-context map via {@link InboundDispatchScope#install},
 *       merged with an ambient {@link InvocationOrigin} so
 *       {@link dev.vertique.services.interceptor.ServiceAuthorizationInterceptor} and any other
 *       in-scope reader observe the real ingress boundary kind (identity-002 P2.S5b-ii). The merge
 *       is root-ingress-aware (identity-002 review fix W_f): when the decoded context already
 *       carries an upstream {@link InvocationOrigin} or a {@link DeferredExecutionOrigin} (e.g. a
 *       delayed-job, cron, or outbox-relay dispatch), that provenance is preserved/derived instead
 *       of being clobbered, so deferred work stays distinguishable from an ordinary synchronous
 *       call; only when neither is present does this invoker's own boundary
 *       ({@link DispatchBoundary#SERVICE_DISPATCH}) apply. See {@link #withInvocationOrigin} for
 *       the precedence. The MDC service-dispatch decoder registered by
 *       {@code LoggingContextModule} (via {@code ServiceDispatchCodecs.snapshotDecoder})
 *       materialises an {@link MDCContexts}-backed {@link dev.vertique.logging.MDCContext} from the
 *       {@link dev.vertique.logging.DiagnosticContextSnapshot} in the carrier map.</li>
 *   <li>Construct a {@link ServiceDispatchContext} for the current dispatch.</li>
 *   <li>Fire {@link ServiceInterceptor#onDispatch} sync observers.</li>
 *   <li>Chain {@link ServiceInterceptor#beforeDispatch} async handlers in
 *       {@link dev.vertique.core.extension.OrderedExtension} order (phase → priority → orderKey).
 *       Any handler failure short-circuits dispatch.</li>
 *   <li>Execute the {@link DispatchPipeline} (or invoke the method directly if no pipeline).</li>
 *   <li>Map failures through {@link dev.vertique.services.ServiceExceptionMapper}.</li>
 *   <li>Fire {@link ServiceInterceptor#onComplete} sync observers with timing.</li>
 *   <li>Chain {@link ServiceInterceptor#afterDispatch} async handlers independently
 *       (failures are logged).</li>
 *   <li>On failure: chain {@link ServiceInterceptor#recoverError} handlers; first success wins.</li>
 *   <li>Reply with a {@link Result} using the local codec (skipped for
 *       {@link dev.vertique.services.OneWay @OneWay} operations — failures are logged instead).</li>
 *   <li>Close the dispatch-context scope after all async work settles; MDC is restored as part
 *       of the scope close because the decoder installed it under the same scope.</li>
 * </ol>
 *
 * <p>A reply is sent for all failures except {@link VirtualMachineError}, which is re-thrown
 * after the scope is closed. Reply delivery in the VME path is not guaranteed.
 */
@Slf4j
public class ServiceMethodInvoker implements Handler<Message<DispatchEnvelope<?>>> {

    /**
     * Callback for fatal (non-recoverable) errors encountered during dispatch.
     * Allows the owning verticle to react to {@link Error} instances.
     */
    @FunctionalInterface
    public interface FatalErrorHandler {
        /**
         * Called when a fatal (non-VirtualMachineError {@link Error}) is caught during dispatch.
         *
         * @param error the fatal error
         */
        void onFatalError(Throwable error);
    }

    private static final DeliveryOptions REPLY_OPTIONS = new DeliveryOptions().setCodecName("dispatch.result");

    private final ServiceMethodMeta meta;
    private final ServiceExceptionMapper exceptionMapper;
    private final List<ServiceInterceptor> interceptors;
    private final DispatchPipeline pipeline;
    private final FatalErrorHandler fatalErrorHandler;
    private final Vertx vertx;
    private final ServiceDispatchContextRegistry contextRegistry;
    private final InboundDispatchScope inboundScope;
    private final InboundExecutionContextScope inboundExecScope;
    private final dev.vertique.context.WarningThrottle warningThrottle = new dev.vertique.context.WarningThrottle();

    /**
     * Creates a new invoker for a single service operation.
     *
     * @param meta the operation metadata describing the method to invoke
     * @param exceptionMapper failure translation mapper for normalizing exceptions
     * @param interceptors service interceptors in {@link dev.vertique.core.extension.OrderedExtension}
     *                     order (phase → priority → orderKey)
     * @param pipeline the policy pipeline wrapping the invocation, or {@code null} if no policies apply
     * @param fatalErrorHandler optional callback for fatal {@link Error} instances, may be {@code null}
     * @param vertx the Vert.x instance used for fire-and-report dispatch when
     *              {@link DispatchEnvelope#replyAddress()} is non-null; may be {@code null} to disable
     *              fire-and-report
     */
    public ServiceMethodInvoker(
            @NonNull ServiceMethodMeta meta,
            @NonNull ServiceExceptionMapper exceptionMapper,
            @NonNull List<ServiceInterceptor> interceptors,
            DispatchPipeline pipeline,
            FatalErrorHandler fatalErrorHandler,
            Vertx vertx) {
        this(meta, exceptionMapper, interceptors, pipeline, fatalErrorHandler, vertx, null);
    }

    /**
     * Full constructor including the optional service-dispatch context registry used to decode
     * inbound carrier values into typed bindings (FR-CTX-072–076).
     *
     * @param contextRegistry the service-dispatch context registry, or {@code null} to skip
     *                        decoder-driven validation (legacy behavior — caller-supplied raw
     *                        carrier values are installed verbatim)
     */
    public ServiceMethodInvoker(
            @NonNull ServiceMethodMeta meta,
            @NonNull ServiceExceptionMapper exceptionMapper,
            @NonNull List<ServiceInterceptor> interceptors,
            DispatchPipeline pipeline,
            FatalErrorHandler fatalErrorHandler,
            Vertx vertx,
            ServiceDispatchContextRegistry contextRegistry) {
        this(meta, exceptionMapper, interceptors, pipeline, fatalErrorHandler, vertx, contextRegistry, null);
    }

    /**
     * Full constructor including the injected {@link InboundDispatchScope} used to install the
     * sanitized dispatch-context map per-dispatch (FR-CTX-071) and to read by-FQCN raw values
     * during reflective arg extraction. Pass {@code null} to fall back to the framework's static
     * scope helpers (used by legacy tests that bypass Dagger).
     *
     * @param inboundScope injected inbound dispatch scope, or {@code null} for static fallback
     */
    public ServiceMethodInvoker(
            @NonNull ServiceMethodMeta meta,
            @NonNull ServiceExceptionMapper exceptionMapper,
            @NonNull List<ServiceInterceptor> interceptors,
            DispatchPipeline pipeline,
            FatalErrorHandler fatalErrorHandler,
            Vertx vertx,
            ServiceDispatchContextRegistry contextRegistry,
            InboundDispatchScope inboundScope) {
        this(
                meta,
                exceptionMapper,
                interceptors,
                pipeline,
                fatalErrorHandler,
                vertx,
                contextRegistry,
                inboundScope,
                null);
    }

    /**
     * Full Dagger constructor: includes the {@link InboundExecutionContextScope} substrate
     * lifecycle helper used to compose the inbound install with all registered
     * {@link dev.vertique.core.context.InboundContextInitializer}s (e.g.
     * {@code CorrelationContextSeeder}) in one scope.
     *
     * <p>When {@code inboundExecScope} is {@code null} (legacy test constructors that bypass
     * Dagger), the dispatch path falls back to the plain {@link InboundDispatchScope#install}
     * call. Production wiring through {@code ServiceVerticle} / {@code ServiceDeploymentManager}
     * always provides a non-null helper.
     *
     * @param inboundExecScope substrate lifecycle helper, or {@code null} for legacy fallback
     */
    public ServiceMethodInvoker(
            @NonNull ServiceMethodMeta meta,
            @NonNull ServiceExceptionMapper exceptionMapper,
            @NonNull List<ServiceInterceptor> interceptors,
            DispatchPipeline pipeline,
            FatalErrorHandler fatalErrorHandler,
            Vertx vertx,
            ServiceDispatchContextRegistry contextRegistry,
            InboundDispatchScope inboundScope,
            InboundExecutionContextScope inboundExecScope) {
        this.meta = meta;
        this.exceptionMapper = exceptionMapper;
        this.interceptors = List.copyOf(interceptors);
        this.pipeline = pipeline;
        this.fatalErrorHandler = fatalErrorHandler;
        this.vertx = vertx;
        this.contextRegistry = contextRegistry;
        this.inboundScope = inboundScope != null ? inboundScope : new InboundDispatchScope();
        this.inboundExecScope = inboundExecScope;
    }

    /**
     * Creates a new invoker for a single service operation without a Vert.x reference.
     * Fire-and-report dispatch is not available when using this constructor.
     *
     * @param meta the operation metadata describing the method to invoke
     * @param exceptionMapper failure translation mapper for normalizing exceptions
     * @param interceptors service interceptors in {@link dev.vertique.core.extension.OrderedExtension}
     *                     order (phase → priority → orderKey)
     * @param pipeline the policy pipeline wrapping the invocation, or {@code null} if no policies apply
     * @param fatalErrorHandler optional callback for fatal {@link Error} instances, may be {@code null}
     */
    public ServiceMethodInvoker(
            @NonNull ServiceMethodMeta meta,
            @NonNull ServiceExceptionMapper exceptionMapper,
            @NonNull List<ServiceInterceptor> interceptors,
            DispatchPipeline pipeline,
            FatalErrorHandler fatalErrorHandler) {
        this(meta, exceptionMapper, interceptors, pipeline, fatalErrorHandler, null);
    }

    /**
     * Creates a new invoker without a fatal error handler or Vert.x reference.
     *
     * @param meta the operation metadata describing the method to invoke
     * @param exceptionMapper failure translation mapper for normalizing exceptions
     * @param interceptors service interceptors in {@link dev.vertique.core.extension.OrderedExtension}
     *                     order (phase → priority → orderKey)
     * @param pipeline the policy pipeline wrapping the invocation, or {@code null} if no policies apply
     */
    public ServiceMethodInvoker(
            @NonNull ServiceMethodMeta meta,
            @NonNull ServiceExceptionMapper exceptionMapper,
            @NonNull List<ServiceInterceptor> interceptors,
            DispatchPipeline pipeline) {
        this(meta, exceptionMapper, interceptors, pipeline, null, null);
    }

    // --- Handler Entry Point ---

    @Override
    public void handle(Message<DispatchEnvelope<?>> message) {
        DispatchEnvelope<?> body = message.body();
        Instant startTime = Instant.now();

        // Decode and install the dispatch-context map. The MDC decoder (registered by
        // LoggingContextModule via ServiceDispatchCodecs.snapshotDecoder) materialises an
        // MDCContext from the DiagnosticContextSnapshot in the carrier and installs it under
        // MDCContext.class.getName() inside the same scope. MDC restoration therefore happens
        // automatically when contextScope closes — no separate mdcScope is needed.
        //
        // When the substrate's InboundExecutionContextScope is available (production wiring),
        // installDispatch runs the inbound install AND all registered InboundContextInitializers
        // (e.g. CorrelationContextSeeder for FR-COR-125 first-ingress seeding when no upstream
        // value was decoded) in one composed scope. Legacy test constructors that bypass Dagger
        // fall back to the plain InboundDispatchScope.install path.
        //
        // decodeDispatchContext catches decoder exceptions itself and treats them as throttled
        // drops; the surrounding try is defense-in-depth for non-decoder paths.
        final ContextHolder.Scope contextScope;
        try {
            Map<String, Object> sanitized =
                    decodeDispatchContext(body.metadata().dispatchContext());
            Map<String, Object> withOrigin = withInvocationOrigin(sanitized, DispatchBoundary.SERVICE_DISPATCH);
            contextScope = inboundExecScope != null
                    ? inboundExecScope.installDispatch(withOrigin, DispatchBoundary.SERVICE_DISPATCH)
                    : inboundScope.install(withOrigin);
        } catch (Throwable t) {
            throw t;
        }
        // The dispatch context where the holder values are bound. The terminal hook and scope close
        // must run here, even if a user afterDispatch/recoverError future completes on a foreign context
        // (otherwise onTerminalComplete — and the audit holder read it relies on — would run off-context).
        final Context dispatchContext = Vertx.currentContext();

        try {
            // Build the dispatch context for interceptors
            ServiceDispatchContext dispatchCtx = new ServiceDispatchContext(
                    meta.address(),
                    meta.stableTargetId(),
                    meta.namespace(),
                    meta.name(),
                    meta.operation(),
                    body,
                    meta.oneWay(),
                    meta.methodAnnotations(),
                    meta.classAnnotations(),
                    Map.of());

            // 1. Chain beforeDispatch → fire onDispatch → execute → map to Result
            chainBeforeDispatch(dispatchCtx)
                    .compose(ctx -> {
                        fireOnDispatch(ctx);
                        return execute(body)
                                .map(value -> (Result<?>) Result.success(value))
                                .recover(cause -> {
                                    if (cause instanceof VirtualMachineError vme) {
                                        contextScope.close();
                                        throw vme;
                                    }
                                    if (cause instanceof Error error) {
                                        notifyFatalError(error);
                                    }
                                    Throwable mapped = exceptionMapper.translate(cause);
                                    return Future.succeededFuture(Result.failure(mapped));
                                })
                                .map(result -> new ResultWithContext(ctx, result));
                    })
                    .onComplete(ar -> {
                        Instant endTime = Instant.now();
                        ServiceDispatchContext finalCtx =
                                ar.succeeded() ? ar.result().ctx() : dispatchCtx;
                        Result<?> result = ar.succeeded() ? ar.result().result() : Result.failure(ar.cause());

                        // 3. Fire onComplete sync observers (success or failure, with timing)
                        fireOnComplete(finalCtx, result, startTime, endTime);

                        // 4. Fire afterDispatch async handlers and collect their join future so
                        //    the dispatch-context scope (FR-CTX-071) stays open until they settle.
                        Future<Void> afterDispatchSettled = fireAfterDispatch(finalCtx, result);

                        // 5. Reply or recover. Capture the reply/recovery future so the scope
                        //    close happens only AFTER afterDispatch ALSO settles. The terminal
                        //    (post-recovery) result is captured for onTerminalComplete: it defaults to
                        //    the handler result (correct for the success path) and is overwritten by the
                        //    recovery decision below (recovered -> success(null); not recovered -> failure).
                        // Carries the terminal result across the reply/recovery callback boundary to the
                        // step-6 read. Written in the recovery handlers (which may settle on a foreign
                        // context) and read after re-entering the dispatch context, so AtomicReference gives
                        // the cross-callback visibility a plain local cannot.
                        final AtomicReference<Result<?>> terminalResult = new AtomicReference<>(result);
                        Future<Void> replyOrRecover;
                        try {
                            if (result.isFailure()) {
                                replyOrRecover = chainRecoverError(finalCtx, result.cause())
                                        .onSuccess(v -> {
                                            Result<?> recoveryResult = Result.success(null);
                                            terminalResult.set(recoveryResult);
                                            if (body.replyAddress().isPresent() && vertx != null) {
                                                sendToReplyAddress(
                                                        body.replyAddress().orElseThrow(), recoveryResult);
                                            } else if (!meta.oneWay()) {
                                                try {
                                                    message.reply(recoveryResult, REPLY_OPTIONS);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "[{}] Failed to send recovery reply for operation {}",
                                                            meta.address(),
                                                            meta.operation(),
                                                            e);
                                                }
                                            }
                                        })
                                        .onFailure(finalCause -> {
                                            Result<?> failResult = Result.failure(finalCause);
                                            terminalResult.set(failResult);
                                            if (body.replyAddress().isPresent() && vertx != null) {
                                                sendToReplyAddress(
                                                        body.replyAddress().orElseThrow(), failResult);
                                            } else if (meta.oneWay()) {
                                                log.warn(
                                                        "[{}] One-way operation {} failed: {}",
                                                        meta.address(),
                                                        meta.operation(),
                                                        finalCause.getClass().getSimpleName(),
                                                        finalCause);
                                            } else {
                                                try {
                                                    message.reply(failResult, REPLY_OPTIONS);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "[{}] Failed to send error reply for operation {}",
                                                            meta.address(),
                                                            meta.operation(),
                                                            e);
                                                }
                                            }
                                        })
                                        .recover(c -> Future.succeededFuture())
                                        .mapEmpty();
                            } else {
                                if (body.replyAddress().isPresent() && vertx != null) {
                                    sendToReplyAddress(body.replyAddress().orElseThrow(), result);
                                } else if (!meta.oneWay()) {
                                    message.reply(result, REPLY_OPTIONS);
                                }
                                replyOrRecover = Future.succeededFuture();
                            }
                        } catch (Exception e) {
                            log.warn("[{}] Failed to send reply for operation {}", meta.address(), meta.operation(), e);
                            replyOrRecover = Future.succeededFuture();
                        }

                        // 6. Fire onTerminalComplete with the terminal (post-recovery) outcome while the
                        //    dispatch-context scope is still open, then close the scope — only when BOTH
                        //    afterDispatch and reply/recovery settle. Re-enter the captured dispatch context
                        //    first: a user afterDispatch/recoverError future may settle on a foreign context,
                        //    and the terminal hook (plus the holder read the audit bridge relies on) and the
                        //    scope close must run on the context where the holder values are bound.
                        Future.all(afterDispatchSettled, replyOrRecover)
                                .recover(c -> Future.succeededFuture())
                                .onComplete(v -> runOnDispatchContext(dispatchContext, () -> {
                                    try {
                                        if (!interceptors.isEmpty()) {
                                            fireOnTerminalComplete(
                                                    finalCtx, terminalResult.get(), startTime, Instant.now());
                                        }
                                    } finally {
                                        contextScope.close();
                                    }
                                }));
                    });
        } catch (Throwable t) {
            contextScope.close();
            throw t;
        }
    }

    // --- Invocation Origin ---

    /**
     * Merges an ambient {@link InvocationOrigin} entry — keyed by its FQCN, matching the raw
     * carrier shape {@link InboundDispatchScope#install(Map)} /
     * {@link InboundExecutionContextScope#installDispatch(Map, String)} expect — into the decoded
     * dispatch-context map, so an invocation origin is always the ambient invocation origin for the
     * lifetime of the same install scope (identity-002 P2.S5b-ii). Readers such as
     * {@link dev.vertique.services.interceptor.ServiceAuthorizationInterceptor} observe it via
     * {@code contextHolder.current(InvocationOrigin.class)}.
     *
     * <p>The merged origin is resolved by {@link #resolveInvocationOrigin} — root-ingress-aware
     * rather than an unconditional overwrite (identity-002 review fix W_f): a delayed-job, cron, or
     * outbox-relay dispatch carries a {@link DeferredExecutionOrigin} (installed by the trusted
     * framework boundary that produced it — {@code DelayedJobPoller}, {@code CronJobDispatcher}, or
     * {@code ServiceOutboxDestinationHandler} — none of which depend on {@code vertique-security-core}
     * and so cannot install an {@link InvocationOrigin} directly) and must remain distinguishable
     * from an ordinary synchronous service-dispatch call; only when neither an upstream
     * {@link InvocationOrigin} nor a {@link DeferredExecutionOrigin} is present does {@code boundary}
     * (this invoker's own {@link DispatchBoundary#SERVICE_DISPATCH}) apply.
     *
     * @param sanitized the decoded dispatch-context map to merge into; never mutated in place
     * @param boundary  the {@link DispatchBoundary} constant identifying this invoker's boundary,
     *                  used only when no upstream provenance is present in {@code sanitized}
     * @return a new map containing every entry in {@code sanitized} plus the invocation-origin entry
     */
    private static Map<String, Object> withInvocationOrigin(Map<String, Object> sanitized, String boundary) {
        Map<String, Object> merged = new HashMap<>(sanitized);
        merged.put(InvocationOrigin.class.getName(), resolveInvocationOrigin(sanitized, boundary));
        return merged;
    }

    /**
     * Resolves the {@link InvocationOrigin} to seed for this dispatch, preferring root-ingress
     * provenance already present in the decoded dispatch-context map over this invoker's own
     * boundary (identity-002 review fix W_f).
     *
     * <p>Precedence:
     *
     * <ol>
     *   <li>An upstream {@link InvocationOrigin} already keyed by its FQCN in {@code sanitized} is
     *       preserved as-is (not reconstructed) — a future boundary that propagates its own
     *       ambient origin across a dispatch hop must not be overwritten here.
     *   <li>A {@link DeferredExecutionOrigin} keyed by its FQCN in {@code sanitized} — installed by
     *       a trusted deferred-execution boundary ({@code DelayedJobPoller}, {@code CronJobDispatcher},
     *       {@code ServiceOutboxDestinationHandler}) — is mapped faithfully to an
     *       {@link InvocationOrigin} of the same {@link DeferredExecutionOrigin#kind()} (e.g.
     *       {@link DispatchBoundary#DELAYED_JOB} for delayed-job dispatches), so deferred work stays
     *       distinguishable from an ordinary call.
     *   <li>Otherwise, {@code boundary} (this invoker's own boundary, normally
     *       {@link DispatchBoundary#SERVICE_DISPATCH}) is used, preserving the pre-existing
     *       behavior for a dispatch with no upstream provenance.
     * </ol>
     *
     * <p><strong>Narrow-only contract:</strong> the resolved {@link InvocationOrigin} is advisory
     * framework provenance, not a trust boundary. A policy consuming it (e.g.
     * {@link dev.vertique.services.interceptor.ServiceAuthorizationInterceptor}) MUST only ever use
     * it to <em>narrow</em> (deny) a decision, never to <em>widen</em> (permit) one — treating a
     * spoofable-looking origin as grounds to grant access would be a privilege-escalation path. Both
     * branches above only ever read {@code kind}/{@code type} values written by trusted,
     * framework-internal dispatch boundaries (never from caller-supplied wire bytes — no
     * {@code ServiceDispatchContextDecoder} is registered for either {@link InvocationOrigin} or
     * {@link DeferredExecutionOrigin}, so an entry under either FQCN key that is not already an
     * instance of the expected type is ignored by the {@code instanceof} pattern match below rather
     * than trusted).
     *
     * @param sanitized the decoded dispatch-context map to inspect for upstream provenance
     * @param boundary  this invoker's own boundary, used as the fallback
     * @return the resolved {@link InvocationOrigin}; never {@code null}
     */
    private static InvocationOrigin resolveInvocationOrigin(Map<String, Object> sanitized, String boundary) {
        if (sanitized.get(InvocationOrigin.class.getName()) instanceof InvocationOrigin upstream) {
            return upstream;
        }
        if (sanitized.get(DeferredExecutionOrigin.class.getName()) instanceof DeferredExecutionOrigin deferred) {
            return InvocationOrigin.of(deferred.kind());
        }
        return InvocationOrigin.of(boundary);
    }

    // --- Interceptor Chains ---

    /**
     * Chains {@link ServiceInterceptor#beforeDispatch} handlers sequentially.
     * Each handler receives the (possibly updated) context from the previous handler.
     * Returns a failed future on the first handler failure (short-circuits remaining handlers).
     *
     * <p>Delegates to {@link Combinators#foldSequential}, whose sequential threading and
     * fail-fast short-circuit semantics match the prior hand-rolled fold exactly.
     *
     * @param ctx the current dispatch context (the fold seed)
     * @return a future of the final (possibly updated) context, or a failed future on first failure
     */
    private Future<ServiceDispatchContext> chainBeforeDispatch(ServiceDispatchContext ctx) {
        return Combinators.foldSequential(interceptors, ctx, (interceptor, c) -> interceptor.beforeDispatch(c));
    }

    /**
     * Chains {@link ServiceInterceptor#recoverError} handlers sequentially.
     * The first interceptor that returns a succeeded future wins; later interceptors are skipped.
     *
     * <p>A failure marked {@link NonRecoverableDispatchFailure} (e.g. the action-gate's
     * authorization deny) bypasses the chain entirely and propagates unchanged — recovery can never
     * resurrect it (fail-closed invariant). The check runs before every step so a non-recoverable
     * failure produced <em>by</em> an interceptor's {@code recoverError} (which may return a failed
     * future) is also honoured, not just the original {@code error}.
     *
     * <p>Delegates to {@link Combinators#recoverFirstWins}: first-success-wins, a recoverer's own
     * failure threads to the next, and the {@code NonRecoverableDispatchFailure} predicate
     * short-circuits before every step — matching the prior hand-rolled recover chain exactly.
     *
     * @param ctx   the dispatch context at the time of failure
     * @param error the dispatch failure to recover from
     * @return a succeeded future if any interceptor recovers, or a failed future with the original error
     */
    private Future<Void> chainRecoverError(ServiceDispatchContext ctx, Throwable error) {
        return Combinators.recoverFirstWins(
                interceptors,
                error,
                (interceptor, cause) -> interceptor.recoverError(ctx, cause),
                NonRecoverableDispatchFailure.class::isInstance);
    }

    // --- Sync Observers ---

    /**
     * Fires {@link ServiceInterceptor#onDispatch} on all interceptors. Exceptions are swallowed.
     *
     * @param ctx the dispatch context
     */
    private void fireOnDispatch(ServiceDispatchContext ctx) {
        Combinators.forEachSwallowSync(
                interceptors,
                interceptor -> interceptor.onDispatch(ctx),
                (interceptor, e) -> log.warn(
                        "[{}] onDispatch interceptor {} threw synchronously",
                        meta.address(),
                        interceptor.getClass().getSimpleName(),
                        e));
    }

    /**
     * Fires {@link ServiceInterceptor#onComplete} on all interceptors. Exceptions are swallowed.
     *
     * @param ctx       the dispatch context
     * @param result    the dispatch result (success or failure)
     * @param startTime when dispatch started
     * @param endTime   when dispatch completed
     */
    private void fireOnComplete(ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {
        Combinators.forEachSwallowSync(
                interceptors,
                interceptor -> interceptor.onComplete(ctx, result, startTime, endTime),
                (interceptor, e) -> log.warn(
                        "[{}] onComplete interceptor {} threw synchronously",
                        meta.address(),
                        interceptor.getClass().getSimpleName(),
                        e));
    }

    /**
     * Fires {@link ServiceInterceptor#onTerminalComplete} on all interceptors with the terminal
     * (post-recovery) outcome. Exceptions are swallowed.
     *
     * @param ctx       the dispatch context
     * @param result    the terminal dispatch result (success, recovered, or failed)
     * @param startTime when dispatch started
     * @param endTime   when the terminal outcome was reached (after the recovery decision)
     */
    private void fireOnTerminalComplete(
            ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {
        Combinators.forEachSwallowSync(
                interceptors,
                interceptor -> interceptor.onTerminalComplete(ctx, result, startTime, endTime),
                (interceptor, e) -> log.warn(
                        "[{}] onTerminalComplete interceptor {} threw synchronously",
                        meta.address(),
                        interceptor.getClass().getSimpleName(),
                        e));
    }

    /**
     * Runs {@code task} on {@code dispatchContext} — the context where the dispatch-scope holder values are
     * bound. Runs inline when already on that context (the common case); otherwise re-enters it via
     * {@link Context#runOnContext}. Guards the dispatch tail (terminal observers + scope close) against a
     * user {@code afterDispatch}/{@code recoverError} future settling on a foreign Vert.x context.
     *
     * @param dispatchContext the dispatch context to run on; may be {@code null} (then runs inline)
     * @param task            the work to run on the dispatch context
     */
    private void runOnDispatchContext(Context dispatchContext, Runnable task) {
        if (dispatchContext != null && Vertx.currentContext() != dispatchContext) {
            dispatchContext.runOnContext(ignored -> task.run());
        } else {
            task.run();
        }
    }

    /**
     * Fires {@link ServiceInterceptor#afterDispatch} on all interceptors independently and
     * returns a {@link Future} that completes when all interceptor futures settle. Failures
     * from individual interceptors are logged at WARN and do not affect the returned future —
     * the caller can safely chain {@code .eventually(scope::close)} so the dispatch-context
     * scope (FR-CTX-071) remains open for the lifetime of afterDispatch async work.
     *
     * @param ctx    the dispatch context
     * @param result the dispatch result (success or failure)
     * @return a future that completes (always succeeded) when all afterDispatch futures settle
     */
    private Future<Void> fireAfterDispatch(ServiceDispatchContext ctx, Result<?> result) {
        // Delegates to Combinators.joinAllSwallow: all afterDispatch hooks are issued, the join waits
        // for all of them to settle, and per-interceptor failures are swallowed (never failing the
        // dispatch). The two WARN messages are preserved for the cases they cover: a synchronous
        // throw is caught and logged "threw synchronously" inside the hook (then contributes an
        // instantly-succeeded future so it stays out of onFailure), while an async failure is logged
        // "failed" by onFailure. A null-returning afterDispatch (an SPI violation) is now routed
        // through the kernel's null-future-as-failure handling to onFailure and logs the "failed"
        // message — consistent with that kernel contract, and no failure is dropped. joinAllSwallow
        // returns a succeeded future for an empty list, so no explicit isEmpty guard is needed here.
        return Combinators.joinAllSwallow(
                interceptors,
                interceptor -> {
                    try {
                        return interceptor.afterDispatch(ctx, result);
                    } catch (Exception e) {
                        log.warn(
                                "[{}] afterDispatch interceptor {} threw synchronously",
                                meta.address(),
                                interceptor.getClass().getSimpleName(),
                                e);
                        return Future.succeededFuture();
                    }
                },
                (interceptor, cause) -> log.warn(
                        "[{}] afterDispatch interceptor {} failed",
                        meta.address(),
                        interceptor.getClass().getSimpleName(),
                        cause));
    }

    // --- Dispatch Execution ---

    /**
     * Executes the dispatch — either through the policy pipeline or directly.
     *
     * @param body the incoming request body
     * @return a future of the raw invocation result
     */
    private Future<Object> execute(DispatchEnvelope<?> body) {
        if (pipeline != null && pipeline.hasStages()) {
            return pipeline.execute(meta, body, () -> invokeMethod(body));
        }
        return invokeMethod(body);
    }

    /**
     * Invokes the service method via reflection, extracting arguments from the body.
     *
     * @param body the incoming request body
     * @return a future of the method's return value (unwrapped from {@code Future<T>})
     */
    private Future<Object> invokeMethod(DispatchEnvelope<?> body) {
        try {
            Object[] args = extractArgs(body);
            Method handler = meta.resolveHandlerMethod();
            handler.trySetAccessible();
            Object rawResult = handler.invoke(meta.serviceInstance(), args);
            if (rawResult instanceof Future<?> future) {
                return future.map(v -> (Object) v);
            }
            // Validation ensures return type is Future<T>, so this branch is a safety net only
            return Future.succeededFuture(rawResult);
        } catch (InvocationTargetException e) {
            return Future.failedFuture(e.getCause());
        } catch (VirtualMachineError vme) {
            throw vme;
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Extracts method arguments from the body according to each parameter's {@link ServiceMethodMeta.ParamSource}.
     *
     * <p>For {@link ServiceMethodMeta.ParamSource#DISPATCH_CONTEXT} parameters, the value is
     * looked up via {@link ServiceMethodMeta.ParamMeta#lookupKey()} — not the parameter type name.
     * This enables subtype-safe {@link dev.vertique.security.SecurityContext} injection: a
     * handler declaring {@code MySecurityContext extends SecurityContext} has a {@code lookupKey}
     * of {@code SecurityContext.class.getName()}, matching the key used by
     * {@link dev.vertique.core.eventbus.DispatchEnvelope} to store the SC.
     *
     * @param body the incoming request body
     * @return the argument array for {@link java.lang.reflect.Method#invoke}
     */
    private Object[] extractArgs(DispatchEnvelope<?> body) {
        List<ParamMeta> params = meta.handlerParams();
        Object[] args = new Object[params.size()];
        for (int i = 0; i < params.size(); i++) {
            ParamMeta param = params.get(i);
            args[i] = switch (param.source()) {
                case PAYLOAD -> body.payload();
                case DISPATCH_CONTEXT -> resolveDispatchContextArg(param);
            };
        }
        return args;
    }

    /**
     * Reads the holder value for the given DISPATCH_CONTEXT parameter and validates it against
     * the declared parameter type via {@code param.type().isInstance(value)} (AC-CTX-006).
     *
     * <p>This is the final type-safety gate before reflection: a wrong-type value that slipped
     * past the decoder/handler-declared validation in {@link #decodeDispatchContext} (e.g., the
     * {@code SecurityContext} key carrying a non-{@code SecurityContext} value) is dropped here
     * with a throttled WARN rather than failing the reflective invocation. The
     * {@code SecurityContext} subtype-injection contract still holds: SC is stored under the
     * canonical {@code SecurityContext.class.getName()} key, and {@code param.type()} for an
     * {@code MySecurityContext extends SecurityContext} parameter accepts the canonical-typed
     * value via {@link Class#isInstance}.
     */
    private Object resolveDispatchContextArg(ParamMeta param) {
        Object raw = inboundScope.currentRawValueByKey(param.lookupKey());
        if (raw == null) {
            return null;
        }
        if (param.type().isInstance(raw)) {
            return raw;
        }
        final Class<?> expected = param.type();
        final Class<?> actual = raw.getClass();
        warningThrottle.once(
                "argExtract|" + meta.address() + "|" + param.lookupKey() + "|" + actual.getName(),
                k -> log.warn(
                        "[{}] Dispatch-context value at key {} is not an instance of declared handler-parameter type {}; got {} — passing null (further occurrences suppressed)",
                        meta.address(),
                        param.lookupKey(),
                        expected.getName(),
                        actual.getName()));
        return null;
    }

    // --- Decoder-driven validation ---

    /**
     * Walks the raw dispatch-context carrier map and, for each entry whose key matches a
     * registered {@link ServiceDispatchContextDecoder#key()}, invokes the decoder to produce the
     * typed binding (FR-CTX-072..076). Entries with no matching decoder pass through unchanged so
     * handler-declared {@code @DispatchContextValue} parameters and the {@link
     * dev.vertique.security.SecurityContext} compatibility path keep working.
     *
     * <p>When no {@link ServiceDispatchContextRegistry} is configured (e.g., legacy tests using
     * the 6-arg constructor), the raw map is returned unchanged.
     *
     * <p>Decode failures (null result, returned warnings) drop the entry with a throttled WARN.
     */
    private Map<String, Object> decodeDispatchContext(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        java.util.HashMap<String, Object> result = null; // lazy: only allocate if we transform

        // Pass 1: invoke registered decoders for matching carrier keys (FR-CTX-072..076).
        if (contextRegistry != null) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                ServiceDispatchContextDecoder<?> decoder = decoderForKey(entry.getKey());
                if (decoder == null) {
                    continue;
                }
                String carrierKey = entry.getKey();
                String typeKey = decoder.type().getName();
                // Relay paths can supply a value already typed under the FQCN key (durable
                // decode already happened upstream). Skip the snapshot decoder in that case —
                // re-running it on a typed value would drop the entry.
                if (decoder.type().isInstance(entry.getValue())) {
                    continue;
                }
                ContextDecodeResult<?> decoded;
                try {
                    decoded = decoder.decode(entry.getValue(), new ServiceDispatchDecodeContext("service-dispatch"));
                } catch (RuntimeException re) {
                    // FR-CTX-051 + FR-CTX-132: a thrown decoder is treated as a decode failure
                    // with a throttled WARN — must not break dispatch.
                    warningThrottle.once(
                            "throw|" + meta.address() + "|" + decoder.getClass().getName(),
                            k -> log.warn(
                                    "[{}] ServiceDispatchContextDecoder {} threw for key {}; dropping entry (further occurrences suppressed)",
                                    meta.address(),
                                    decoder.getClass().getName(),
                                    carrierKey,
                                    re));
                    if (result == null) {
                        result = new java.util.HashMap<>(raw);
                    }
                    result.remove(carrierKey);
                    continue;
                }
                if (decoded == null) {
                    warningThrottle.once(
                            "null|" + meta.address() + "|" + decoder.getClass().getName(),
                            k -> log.warn(
                                    "[{}] ServiceDispatchContextDecoder {} returned null for key {} (FR-CTX-051) — dropping entry (further occurrences suppressed)",
                                    meta.address(),
                                    decoder.getClass().getName(),
                                    carrierKey));
                    if (result == null) {
                        result = new java.util.HashMap<>(raw);
                    }
                    result.remove(carrierKey);
                    continue;
                }
                if (result == null) {
                    result = new java.util.HashMap<>(raw);
                }
                result.remove(carrierKey);
                if (decoded.value().isPresent()) {
                    result.put(typeKey, decoded.value().orElseThrow());
                } else if (!decoded.warnings().isEmpty() && log.isWarnEnabled()) {
                    for (var warning : decoded.warnings()) {
                        warningThrottle.once(
                                "warn|" + meta.address() + "|"
                                        + decoder.getClass().getName() + "|" + warning.reason(),
                                k -> log.warn(
                                        "[{}] Decoder {} for key {} warning: reason='{}' (further occurrences with same reason suppressed)",
                                        meta.address(),
                                        decoder.getClass().getName(),
                                        carrierKey,
                                        warning.reason()));
                    }
                }
            }
        }

        // Pass 2: validate handler-declared @DispatchContextValue parameter types via
        // type.isInstance(value) for entries that have no registered decoder (FR-CTX-075).
        // Wrong-type values must not reach reflection invocation — drop with a throttled WARN.
        Map<String, Object> view = result == null ? raw : result;
        for (ParamMeta param : meta.handlerParams()) {
            if (param.source() != ServiceMethodMeta.ParamSource.DISPATCH_CONTEXT) {
                continue;
            }
            String key = param.lookupKey();
            Object value = view.get(key);
            if (value == null) {
                continue;
            }
            // Decoder-validated entries are already typed correctly. SecurityContext-keyed entries
            // are filtered by isInstance at extractArgs time against the declared subtype.
            if (contextRegistry != null && decoderForKey(key) != null) {
                continue;
            }
            if (param.lookupKey().equals(dev.vertique.security.SecurityContext.class.getName())) {
                continue;
            }
            if (!param.type().isInstance(value)) {
                final Class<?> expected = param.type();
                final Class<?> actual = value.getClass();
                warningThrottle.once(
                        "isInstance|" + meta.address() + "|" + key + "|" + actual.getName(),
                        k -> log.warn(
                                "[{}] Dispatch-context entry for key {} is not an instance of declared handler-parameter type {}; got {} — dropping (further occurrences for this (key, actual-type) suppressed)",
                                meta.address(),
                                key,
                                expected.getName(),
                                actual.getName()));
                if (result == null) {
                    result = new java.util.HashMap<>(raw);
                }
                result.remove(key);
            }
        }

        return result == null ? raw : Map.copyOf(result);
    }

    private ServiceDispatchContextDecoder<?> decoderForKey(String key) {
        for (ServiceDispatchContextDecoder<?> decoder : contextRegistry.decoders()) {
            if (decoder.key().equals(key)) {
                return decoder;
            }
        }
        return null;
    }

    // --- Fire-and-Report ---

    /**
     * Publishes a {@link Result} to the given event bus address for fire-and-report dispatch.
     * Used when {@link DispatchEnvelope#replyAddress()} is set instead of calling {@code message.reply()}.
     *
     * <p>Send failures are logged at WARN and do not affect the dispatch outcome.
     *
     * @param replyAddress the target event bus address
     * @param result       the result to send
     */
    private void sendToReplyAddress(String replyAddress, Result<?> result) {
        try {
            vertx.eventBus().send(replyAddress, DispatchEnvelope.of(result), REPLY_OPTIONS);
        } catch (Exception e) {
            log.warn(
                    "[{}] Failed to publish result to reply address '{}' for operation {}",
                    meta.address(),
                    replyAddress,
                    meta.operation(),
                    e);
        }
    }

    // --- Fatal Error Handling ---

    /**
     * Notifies the {@link FatalErrorHandler} of a fatal (non-VirtualMachineError {@link Error}).
     *
     * @param error the fatal error to report
     */
    private void notifyFatalError(Throwable error) {
        if (fatalErrorHandler != null) {
            try {
                fatalErrorHandler.onFatalError(error);
            } catch (Exception e) {
                log.error("[{}] FatalErrorHandler threw while handling error", meta.address(), e);
            }
        } else {
            log.error("[{}] Fatal error during dispatch (no FatalErrorHandler configured)", meta.address(), error);
        }
    }

    // --- Internal types ---

    /**
     * Pairs a (possibly updated) {@link ServiceDispatchContext} with the dispatch {@link Result},
     * allowing both to be threaded through the async chain.
     *
     * @param ctx    the dispatch context after all {@code beforeDispatch} handlers have run
     * @param result the dispatch result (success or failure)
     */
    private record ResultWithContext(ServiceDispatchContext ctx, Result<?> result) {}
}
