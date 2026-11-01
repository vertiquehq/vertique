// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalNoMatch;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A transport-neutral description of a correlated signal source, run one message at a time by
 * {@link WorkflowSignalSourceDelivery#deliver}.
 *
 * <p><strong>INTERNAL.</strong> This type is an internal seam, outside the maturity promise of
 * {@code vertique-workflow-services}. It is public only because a transport in another repository
 * needs it; do not depend on it from application code.
 *
 * <p>A plan is immutable and is made by a builder that has checked the source's construction rules.
 * Obtain a plan builder from {@link #builder(String, Class)}; a transport extends
 * {@link AbstractSignalSourceBuilder} instead and builds the plan from its own {@code build()}.
 *
 * @param <T> the type of the decoded message body
 */
public final class SignalSourcePlan<T> {

    /** The bound of a lookup or identity stage when the source sets none, in milliseconds. */
    static final long DEFAULT_LOOKUP_TIMEOUT_MS = 5000;

    /** The least bound a source may set, in milliseconds. */
    static final long MIN_LOOKUP_TIMEOUT_MS = 1;

    private final String sourceName;
    private final @Nullable Predicate<T> when;
    private final @Nullable Function<SignalMessage<T>, Optional<String>> scope;
    private final @Nullable Function<T, String> dedupBy;
    private final long lookupTimeoutMs;
    private final @Nullable Function<SignalMessage<T>, Future<Optional<SecurityIdentity>>> dispatchAs;
    private final boolean restoreCorrelationFromHeaders;
    private final List<Target<T>> targets;

    SignalSourcePlan(
            String sourceName,
            @Nullable Predicate<T> when,
            @Nullable Function<SignalMessage<T>, Optional<String>> scope,
            @Nullable Function<T, String> dedupBy,
            long lookupTimeoutMs,
            @Nullable Function<SignalMessage<T>, Future<Optional<SecurityIdentity>>> dispatchAs,
            boolean restoreCorrelationFromHeaders,
            List<Target<T>> targets) {
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.when = when;
        this.scope = scope;
        this.dedupBy = dedupBy;
        this.lookupTimeoutMs = lookupTimeoutMs;
        this.dispatchAs = dispatchAs;
        this.restoreCorrelationFromHeaders = restoreCorrelationFromHeaders;
        this.targets = List.copyOf(targets);
    }

    /**
     * Starts a plan builder.
     *
     * @param sourceName the name of the source, which every construction failure and every event names
     * @param messageType the type of the decoded message body
     * @param <T> the message type
     * @return a builder that returns the plan from {@code build()}
     */
    public static <T> Builder<T> builder(String sourceName, Class<T> messageType) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(messageType, "messageType");
        return new PlanBuilder<>(sourceName, messageType);
    }

    /**
     * Returns the name of the source.
     *
     * @return the source name
     */
    public String sourceName() {
        return sourceName;
    }

    /**
     * Returns the bound of a lookup and of the identity function, in milliseconds.
     *
     * @return the effective bound; 5000 unless the source set another
     */
    public long lookupTimeoutMs() {
        return lookupTimeoutMs;
    }

    /**
     * Tells whether the source restores the correlation of a message from its headers.
     *
     * @return {@code true} when the source asked for it
     */
    public boolean restoresCorrelationFromHeaders() {
        return restoreCorrelationFromHeaders;
    }

    @Nullable
    Predicate<T> when() {
        return when;
    }

    @Nullable
    Function<SignalMessage<T>, Optional<String>> scope() {
        return scope;
    }

    @Nullable
    Function<T, String> dedupBy() {
        return dedupBy;
    }

    @Nullable
    Function<SignalMessage<T>, Future<Optional<SecurityIdentity>>> dispatchAs() {
        return dispatchAs;
    }

    List<Target<T>> targets() {
        return targets;
    }

    /**
     * A plan builder: the shared stages and the source-level {@code onNoMatch}, ending in the plan.
     *
     * @param <T> the type of the decoded message body
     */
    public interface Builder<T> extends SourceStages<T, Builder<T>, SignalSourcePlan<T>> {}

    /**
     * How a plan reaches the key of one signal: a function of the decoded message, or a lookup.
     *
     * @param <T> the message type
     * @param contract the workflow contract that declares the signal
     * @param contractName the contract's name as the engine knows it
     * @param signalName the name of the signal
     * @param businessKey whether the key addresses a business key rather than a wait's key
     * @param key the key from the decoded message, or {@code null} when a lookup supplies it
     * @param lookup the key lookup, or {@code null} when {@code key} supplies it
     * @param payload the payload mapping, or {@code null} to send the message body
     * @param onNoMatch what a message with no matching instance does for this signal
     */
    record Target<T>(
            Class<?> contract,
            String contractName,
            String signalName,
            boolean businessKey,
            @Nullable Function<T, String> key,
            @Nullable Function<SignalMessage<T>, Future<Optional<String>>> lookup,
            @Nullable Function<T, ?> payload,
            SignalNoMatch onNoMatch) {}

    private static final class PlanBuilder<T> extends AbstractSignalSourceBuilder<T, Builder<T>, SignalSourcePlan<T>>
            implements Builder<T> {
        private final String sourceName;

        PlanBuilder(String sourceName, Class<T> messageType) {
            super(messageType);
            this.sourceName = sourceName;
        }

        @Override
        public SignalSourcePlan<T> build() {
            return plan(sourceName);
        }
    }
}
