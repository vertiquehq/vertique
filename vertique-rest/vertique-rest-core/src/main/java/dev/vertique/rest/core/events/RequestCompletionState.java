// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Framework-owned completion state of one HTTP request: its start time, the exactly-once emission
 * flag, and the claim that records which transport owns the request and, for a REST operation,
 * which operation.
 *
 * <p>{@link RestRequestCompletionEmitter} creates one state per request through
 * {@link RequestCompletionRecorder}, captures it in its single end-handler closure, and emits from
 * that state alone. The state is also the value of the request's holder in the shared
 * routing-context data, where the recorder's operation-route handler and other-transport claim find
 * it. Nothing outside this package can name the type.
 *
 * <p><strong>Binding.</strong> The state records the {@link HttpServerRequest} it was created for.
 * The recorder's holder lookup returns it only on a routing context whose {@code request()} is that
 * same object, so no path reads or writes the state through another request's routing context.
 *
 * <p><strong>Claim transitions.</strong> These are the only ones:
 * <ul>
 *   <li>an operation route match turns {@code NONE} or {@code REST(op)} into {@code REST(op′)}, and
 *       leaves {@code OTHER} unchanged;</li>
 *   <li>another transport's claim turns {@code NONE} into {@code OTHER}, and leaves {@code REST(op)}
 *       or {@code OTHER} unchanged;</li>
 *   <li>a reroute turns every claim into {@code NONE}. It keeps the start time and never touches the
 *       emitted flag.</li>
 * </ul>
 *
 * <p><strong>Threading.</strong> Claims are written while the request is routed; emission runs on
 * whichever thread ends the response. The claim is one {@code volatile} reference to an immutable
 * value, and the emitted flag is set by compare-and-set, so an emission on another thread sees the
 * last claim written and happens at most once. A transition is a plain read followed by a write: like
 * routing itself, it relies on the request's handlers running one at a time.
 */
final class RequestCompletionState {

    /** When the request's first routing pass reached the emitter. */
    private final Instant startTime;

    /** The request this state was created for; {@code null} only for a context that has no request. */
    @Nullable
    private final HttpServerRequest request;

    /** Set by the one emission that wins the compare-and-set. */
    private final AtomicBoolean emitted = new AtomicBoolean();

    /** The current (claim, operation) value; replaced whole, never mutated. */
    private volatile Claim claim = Claim.NONE;

    /**
     * Creates the state of a request that starts now.
     *
     * @param startTime the request's start time
     * @param request   the request the state is bound to, as {@code ctx.request()} returned it
     */
    RequestCompletionState(Instant startTime, @Nullable HttpServerRequest request) {
        this.startTime = Objects.requireNonNull(startTime, "startTime");
        this.request = request;
    }

    /**
     * Returns the request's start time, taken on its first routing pass.
     *
     * @return the start time; never {@code null}
     */
    Instant startTime() {
        return startTime;
    }

    /**
     * Reports whether this state belongs to {@code candidate}, compared by identity.
     *
     * @param candidate the request of the routing context the state was found on
     * @return {@code true} when {@code candidate} is the request this state was created for
     */
    boolean isBoundTo(@Nullable HttpServerRequest candidate) {
        return request == candidate;
    }

    /**
     * Sets the emitted flag by compare-and-set.
     *
     * @return {@code true} for the one call that sets it, {@code false} once it is set
     */
    boolean markEmitted() {
        return emitted.compareAndSet(false, true);
    }

    /**
     * Returns the current claim.
     *
     * @return the current (claim, operation) value; never {@code null}
     */
    Claim claim() {
        return claim;
    }

    /**
     * Applies an operation route match: {@code NONE} or {@code REST(op)} becomes {@code restClaim};
     * {@code OTHER} is left unchanged.
     *
     * @param restClaim the matched route's {@code REST} claim
     */
    void claimOperation(Claim restClaim) {
        if (claim.kind() != ClaimKind.OTHER) {
            claim = restClaim;
        }
    }

    /**
     * Applies another transport's claim: {@code NONE} becomes {@code OTHER}; {@code REST(op)} and
     * {@code OTHER} are left unchanged.
     */
    void claimForOtherTransport() {
        if (claim.kind() == ClaimKind.NONE) {
            claim = Claim.OTHER;
        }
    }

    /**
     * Applies a reroute: every claim becomes {@code NONE}, so the rerouted target decides. The start
     * time and the emitted flag are kept.
     */
    void reroute() {
        claim = Claim.NONE;
    }

    /** Which transport, if any, has claimed the request. */
    enum ClaimKind {
        /** No transport has claimed the request. */
        NONE,
        /** A REST operation route matched; the claim carries its operation. */
        REST,
        /** A transport other than REST claimed the request. */
        OTHER
    }

    /**
     * An immutable (claim, operation) value.
     *
     * @param kind      which transport, if any, has claimed the request
     * @param operation the matched operation; non-{@code null} exactly for {@link ClaimKind#REST}
     */
    record Claim(ClaimKind kind, @Nullable RestOperationDescriptor operation) {

        /** The unclaimed value. */
        static final Claim NONE = new Claim(ClaimKind.NONE, null);

        /** The value of a request another transport claimed. */
        static final Claim OTHER = new Claim(ClaimKind.OTHER, null);

        /**
         * Rejects a missing kind, and an operation on anything but a {@code REST} claim or its absence
         * on one.
         */
        Claim {
            Objects.requireNonNull(kind, "kind");
            if ((kind == ClaimKind.REST) != (operation != null)) {
                throw new IllegalArgumentException("a claim carries an operation exactly when it is REST: " + kind);
            }
        }

        /**
         * Returns the {@code REST} claim of {@code operation}.
         *
         * @param operation the operation of the matched route
         * @return the claim
         * @throws NullPointerException if {@code operation} is {@code null}
         */
        static Claim rest(RestOperationDescriptor operation) {
            return new Claim(ClaimKind.REST, Objects.requireNonNull(operation, "operation"));
        }
    }
}
