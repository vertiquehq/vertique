// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.exception.UnavailableException;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.exception.RateLimitExceededException;
import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.security.OriginCaptureMiddleware;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional, config-driven {@link Middleware} contributed at {@link MiddlewareScope#ROOT} that
 * applies ordered, composable-dimension admission rules pre-authorization
 * (contracts/rest-adapter.md, "Edge limiter"). Mounted before any {@code RouterMount}/{@code
 * BodyHandler}, and strictly after {@link OriginCaptureMiddleware} at {@link #ORDER}.
 *
 * <p>Rules are evaluated in declared order via {@link RateLimiter#acquire}; the first
 * non-permitting decision wins and stops further evaluation. Tokens already consumed by
 * earlier-permitting rules stay consumed.
 *
 * <p><b>Denial rendering.</b> Vert.x 5's {@code Router.subRouter()} registers every mount as a
 * failure handler for the path it is mounted under, so {@code ctx.fail(status, exception)} raised
 * from this ROOT-scoped middleware — before the request ever reaches a mount's own handler chain —
 * still lands in the matching mount's failure handler (for a JAX-RS mount, {@code
 * JaxRsRouterMount.handleFailure}), which runs the full {@code ErrorPipeline}/{@code
 * ResponsePipeline}: interceptor chains, {@code ProblemDetail} instance enrichment, and profile-aware
 * error serialization — exactly the pipeline a resource method's own thrown exception traverses. This
 * middleware therefore never builds a response itself; it always delegates via {@code ctx.fail} with
 * an explicit status (never the bare {@code ctx.fail(Throwable)} overload, which would stamp {@code
 * 500} regardless of the denial's real status on any path that escapes to Vert.x's own default
 * failure handler):
 *
 * <ul>
 *   <li>{@code QUOTA_EXCEEDED} — {@code ctx.fail(429, new RateLimitExceededException(decision))}</li>
 *   <li>{@code BACKEND_FAILURE_CLOSED} — {@code ctx.fail(503, new
 *       RateLimitUnavailableException(decision))}</li>
 *   <li>The two paths that carry no {@link RateLimitDecision} at all — an absent {@code
 *       RequestOrigin} under {@code failureMode=CLOSED} (no {@code acquire} was ever called) and a
 *       defensive {@code acquire()}-future failure — converge on {@code ctx.fail(503, new
 *       UnavailableException(FIXED_UNAVAILABLE_MESSAGE))}: there is no decision to mint a
 *       rate-limit-specific exception from, so the core {@code UnavailableException} → 503 default
 *       mapping (already relied on for the decision-based path when no rate-limit-specific mapper is
 *       installed) renders them.</li>
 * </ul>
 *
 * <p>{@link RateLimitExceededException}/{@link RateLimitUnavailableException} resolve through the
 * mount's own real {@code ExceptionMapperRegistry} the same way an {@code execute()}/{@code
 * @RateLimited} throw does: the packaged {@code RateLimitExceptionMapper} pair (contributed
 * unconditionally by {@code RestRateLimitModule}) renders them when installed; {@link
 * RateLimitExceededException} additionally extends the core {@code TooManyRequestsException} root
 * (T020), so a graph with no rate-limit-specific mapper still renders {@code 429} (with {@code
 * Retry-After} threaded from the decision) via {@code RestModule.defaultExceptionMapper()}'s core
 * default; {@link RateLimitUnavailableException} already extends {@code UnavailableException} and
 * gets the equivalent {@code 503} default. An application that contributes its own {@code
 * ExceptionMapper<RateLimitExceededException>}/{@code ExceptionMapper<RateLimitUnavailableException>}
 * (or a common supertype) overrides the edge denial's response the same way it already overrides one
 * thrown by {@code execute()} — no separate customization surface to learn. A throwing application
 * mapper falls through to the pipeline's own bare-metal {@code 500} fallback, exactly as it would for
 * an {@code execute()} exception.
 *
 * <p>A denial on a request path matching no mount at all is an accepted degradation: {@code
 * ctx.fail} still carries the correct status to Vert.x's own default (unhandled-failure) response,
 * but that response is plain text, carries no {@code Retry-After}, and is logged at {@code ERROR} by
 * Vert.x — the full pipeline dressing above is reachable only once the request has actually matched a
 * mount.
 */
public final class RateLimitEdgeMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(RateLimitEdgeMiddleware.class);

    /** {@link OriginCaptureMiddleware#ORDER} + 20 (contracts/rest-adapter.md, "Edge limiter"). */
    public static final int ORDER = OriginCaptureMiddleware.ORDER + 20;

    /**
     * Fixed, redacted message for the two decision-less denial paths (absent-origin + {@code CLOSED},
     * defensive {@code acquire()}-future failure) — deliberately the same text {@link
     * RateLimitUnavailableException} itself carries, so a client sees one consistent "backend
     * unavailable" message regardless of whether a {@link RateLimitDecision} existed to mint a
     * rate-limit-specific exception from. Carries no key material, identity, or backend detail.
     */
    private static final String FIXED_UNAVAILABLE_MESSAGE = "Rate limit backend unavailable";

    private final List<CompiledRule> rules;
    private final String path;

    /**
     * Compiles {@code config}'s rules against {@code rateLimiters}, resolving one {@link
     * RateLimiter} handle per rule via {@link RateLimiters#limiter(String)} — an unknown policy
     * name fails synchronously here, exactly as any other {@code limiter(...)} caller observes
     * (contracts/rest-adapter.md, "Configuration").
     *
     * @param config the enabled edge config (callers must not construct this for a disabled config)
     * @param rateLimiters the application-scoped rate-limit runtime
     * @param originCaptureBound whether {@link OriginCaptureMiddleware} is bound in the Dagger
     *     graph; an {@code IP}-dimension rule fails construction when this is {@code false}
     *     (contracts/rest-adapter.md, "Rule composition semantics" — IP mechanism, startup
     *     validation)
     * @throws ConfigurationException if an {@code IP}-dimension rule is declared without {@code
     *     originCaptureBound}, or a rule's declared {@code cost} exceeds its referenced policy's
     *     capacity
     * @throws IllegalArgumentException if a rule references an unknown policy
     */
    public RateLimitEdgeMiddleware(RateLimitEdgeConfig config, RateLimiters rateLimiters, boolean originCaptureBound) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(rateLimiters, "rateLimiters");
        this.path = config.path();
        List<CompiledRule> compiled = new ArrayList<>(config.rules().size());
        for (RateLimitEdgeRule rule : config.rules()) {
            boolean usesIp = rule.key().contains(RateLimitEdgeKeyDimension.IP);
            if (usesIp && !originCaptureBound) {
                throw new ConfigurationException("rateLimit.rest.edge rule for policy '" + rule.policy()
                        + "' uses the IP dimension, but OriginCaptureMiddleware is not bound — "
                        + "co-install vertique-rest-security's AuthModule");
            }
            RateLimiter limiter = rateLimiters.limiter(rule.policy());
            if (rule.cost().isPresent() && rule.cost().getAsLong() > limiter.capacity()) {
                throw new ConfigurationException("rateLimit.rest.edge rule for policy '" + rule.policy()
                        + "' declares cost " + rule.cost().getAsLong() + " exceeding policy capacity "
                        + limiter.capacity());
            }
            // Read directly off the resolved handle (RateLimiter#failureMode()) rather than
            // re-deriving it from a separate configuration source: this classifies correctly for a
            // policy declared only via a Dagger @IntoSet RateLimitPolicy contribution, which carries
            // no rateLimit.policies.<name> JSON section at all (contracts/rate-limit-runtime.md,
            // "Policy model"; RateLimitCoreModule merges config- and programmatic-tier policies
            // before either RateLimiters or this middleware ever sees them).
            RateLimitFailureMode ipFailureMode = usesIp ? limiter.failureMode() : null;
            compiled.add(new CompiledRule(rule, limiter, ipFailureMode));
        }
        this.rules = List.copyOf(compiled);
    }

    @Override
    public int priority() {
        return ORDER;
    }

    @Override
    public MiddlewareScope scope() {
        return MiddlewareScope.ROOT;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public void handle(RoutingContext ctx) {
        // Rule evaluation calls RateLimiter.acquire(...), which may resolve asynchronously (a
        // CLUSTERED backend's network round trip in particular). Vert.x's HttpServerRequest
        // auto-drains and marks itself ended if the handler that first sees it returns to the
        // event loop without a body consumer attached or the stream paused — which would make any
        // later BodyHandler fail ("BodyHandler invoked after the request has ended"). Pausing here
        // and resuming exactly once on every terminal path below (permit-through, 429, 503)
        // preserves the body for whatever runs next, matching Vert.x's own body-preservation
        // contract for a handler that cannot decide synchronously.
        ctx.request().pause();
        evaluate(ctx, 0);
    }

    private void evaluate(RoutingContext ctx, int index) {
        if (index >= rules.size()) {
            ctx.request().resume();
            ctx.next();
            return;
        }
        CompiledRule compiled = rules.get(index);
        KeyOutcome outcome = compiled.deriveKey(ctx);
        if (outcome instanceof Bypassed) {
            evaluate(ctx, index + 1);
            return;
        }
        if (outcome instanceof OriginAbsent) {
            if (compiled.ipFailureMode() == RateLimitFailureMode.CLOSED) {
                ctx.request().resume();
                ctx.fail(503, new UnavailableException(FIXED_UNAVAILABLE_MESSAGE));
            } else {
                // OPEN admits without ever deriving a key or calling acquire() — log internally so
                // an operator can see this rule is silently bypassed (never surfaced to the
                // response body/headers: contracts/rest-adapter.md, "HTTP mapping" carries no
                // policy/rule identity). Policy name only; never the request's key material.
                log.warn(
                        "rate-limit edge rule for policy '{}': RequestOrigin absent, failureMode=OPEN — admitting"
                                + " request without evaluating this rule",
                        compiled.rule().policy());
                evaluate(ctx, index + 1);
            }
            return;
        }
        RateLimitKey key = ((Resolved) outcome).key();
        RateLimitEdgeRule rule = compiled.rule();
        Future<RateLimitDecision> acquired = rule.cost().isPresent()
                ? compiled.limiter().acquire(key, rule.cost().getAsLong())
                : compiled.limiter().acquire(key);
        acquired.onComplete(result -> {
            if (result.failed()) {
                // Defensive-only: acquire() only fails its future for a cost/capacity mismatch
                // (contracts/rate-limit-runtime.md, "Handle semantics"), which eager policy
                // validation should already have ruled out. Fail closed rather than let an
                // unmapped rate-limit exception cross the pre-authorization boundary.
                ctx.request().resume();
                ctx.fail(503, new UnavailableException(FIXED_UNAVAILABLE_MESSAGE));
                return;
            }
            RateLimitDecision decision = result.result();
            switch (decision.outcome()) {
                case PERMITTED, BACKEND_FAILURE_OPEN, DISABLED -> evaluate(ctx, index + 1);
                case QUOTA_EXCEEDED -> {
                    ctx.request().resume();
                    ctx.fail(429, new RateLimitExceededException(decision));
                }
                case BACKEND_FAILURE_CLOSED -> {
                    ctx.request().resume();
                    ctx.fail(503, new RateLimitUnavailableException(decision));
                }
            }
        });
    }

    /**
     * Aggregates an IPv6 {@code clientIp} onto {@code ipv6PrefixBits}; an IPv4 address always keys
     * on the full address regardless of {@code ipv6PrefixBits} (contracts/rest-adapter.md,
     * "Cardinality caution"). {@code clientIp} is guaranteed to be a validated IP literal by {@link
     * RequestOrigin}'s own construction, so {@link InetAddress#getByName} below never triggers a
     * DNS lookup.
     *
     * <p>Package-private (rather than {@code private}) so {@code
     * RateLimitEdgeMiddlewareIpKeyComponentTest} can pin this derivation's golden vectors directly,
     * independent of a full HTTP round trip.
     */
    static String ipKeyComponent(String clientIp, int ipv6PrefixBits) {
        if (!clientIp.contains(":")) {
            return clientIp;
        }
        try {
            byte[] bytes = InetAddress.getByName(clientIp).getAddress();
            if (bytes.length != 16) {
                return clientIp;
            }
            byte[] masked = new byte[16];
            int fullBytes = ipv6PrefixBits / 8;
            int remainderBits = ipv6PrefixBits % 8;
            System.arraycopy(bytes, 0, masked, 0, fullBytes);
            if (remainderBits > 0 && fullBytes < 16) {
                int mask = (0xFF << (8 - remainderBits)) & 0xFF;
                masked[fullBytes] = (byte) (bytes[fullBytes] & mask);
            }
            return InetAddress.getByAddress(masked).getHostAddress() + "/" + ipv6PrefixBits;
        } catch (UnknownHostException e) {
            return clientIp;
        }
    }

    /** Distinct missing-header bucket marker, framed via {@link RateLimitKey}'s enum encoding. */
    private enum MissingHeaderMarker {
        SHARED
    }

    /** One rule compiled against its resolved {@link RateLimiter} handle. */
    private record CompiledRule(RateLimitEdgeRule rule, RateLimiter limiter, RateLimitFailureMode ipFailureMode) {

        /**
         * Derives this rule's key for {@code ctx}, or signals a bypass/origin-absent outcome
         * instead (contracts/rest-adapter.md, "Rule composition semantics").
         */
        KeyOutcome deriveKey(RoutingContext ctx) {
            List<RateLimitEdgeKeyDimension> dimensions = rule.key();
            if (dimensions.size() == 1 && dimensions.get(0) == RateLimitEdgeKeyDimension.GLOBAL) {
                return new Resolved(RateLimitKey.global());
            }
            List<Object> components = new ArrayList<>(dimensions.size() * 2);
            for (RateLimitEdgeKeyDimension dimension : dimensions) {
                switch (dimension) {
                    case IP -> {
                        Object stashed = ctx.get(RequestOrigin.class.getName());
                        if (!(stashed instanceof RequestOrigin origin)) {
                            return OriginAbsent.INSTANCE;
                        }
                        components.add(RateLimitEdgeKeyDimension.IP);
                        components.add(ipKeyComponent(origin.clientIp(), rule.effectiveIpv6PrefixBits()));
                    }
                    case HEADER -> {
                        String headerName = rule.headerName().orElseThrow();
                        MultiMap headers = ctx.request().headers();
                        List<String> values = headers.getAll(headerName);
                        if (values.size() != 1) {
                            if (rule.missingDimension() == MissingDimensionPolicy.BYPASS) {
                                return Bypassed.INSTANCE;
                            }
                            components.add(RateLimitEdgeKeyDimension.HEADER);
                            components.add(MissingHeaderMarker.SHARED);
                        } else {
                            components.add(RateLimitEdgeKeyDimension.HEADER);
                            components.add(values.get(0));
                        }
                    }
                    case GLOBAL ->
                        throw new IllegalStateException(
                                "unreachable: GLOBAL is only valid alone, enforced by RateLimitEdgeRule's constructor");
                }
            }
            return new Resolved(RateLimitKey.of(
                    components.get(0), components.subList(1, components.size()).toArray()));
        }
    }

    /** Outcome of {@link CompiledRule#deriveKey}. */
    private sealed interface KeyOutcome permits Resolved, Bypassed, OriginAbsent {}

    private record Resolved(RateLimitKey key) implements KeyOutcome {}

    private record Bypassed() implements KeyOutcome {
        static final Bypassed INSTANCE = new Bypassed();
    }

    private record OriginAbsent() implements KeyOutcome {
        static final OriginAbsent INSTANCE = new OriginAbsent();
    }
}
