// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.services.source.SignalSourcePlan.Target;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalNoMatch;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Holds the state of every shared stage, so that a transport's builder adds only its own stages and its
 * {@code build()}.
 *
 * <p><strong>INTERNAL.</strong> This type is an internal seam, outside the maturity promise of
 * {@code vertique-workflow-services}. It is public only because a transport in another repository
 * needs it; do not depend on it from application code.
 *
 * <p>A transport's builder extends this class with itself as {@code SELF}, so every shared stage
 * returns the transport's own builder, and implements {@code build()} as {@link #plan(String)}. The
 * construction rules are checked there, once, for every transport: a signal with no key source, a
 * message type that differs from the signal's parameter type with no payload mapping, a business-key
 * signal with no dedup key, a scope beside any-scope, a lookup timeout below 1, and a signal the contract
 * does not declare. A failure names the source and never a key.
 *
 * <p>{@code plan} is idempotent: a second call returns the same plan, and a stage called after it fails
 * with an {@link IllegalStateException} naming the source.
 *
 * @param <T> the type of the decoded message body
 * @param <SELF> the type of the transport's builder
 * @param <R> what the transport's {@code build()} returns
 */
public abstract class AbstractSignalSourceBuilder<T, SELF, R> implements SourceStages<T, SELF, R> {

    private static final WorkflowTypeNames TYPE_NAMES = new DefaultWorkflowTypeNames();

    private final @Nullable Class<T> messageType;
    private final List<Draft<T>> drafts = new ArrayList<>();
    private @Nullable Predicate<T> when;
    private @Nullable Function<SignalMessage<T>, Optional<String>> scope;
    private boolean anyScope;
    private @Nullable Function<T, String> dedupBy;
    private long lookupTimeoutMs = SignalSourcePlan.DEFAULT_LOOKUP_TIMEOUT_MS;
    private @Nullable Function<SignalMessage<T>, Future<Optional<SecurityIdentity>>> dispatchAs;
    private boolean restoreCorrelationFromHeaders;
    private @Nullable SignalNoMatch sourcePolicy;
    private boolean sharedStages;
    private @Nullable SignalSourcePlan<T> built;

    /**
     * Creates the state of a source.
     *
     * @param messageType the type of the decoded message body, or {@code null} when the transport's
     *     decoder does not state it; {@code payload} is then required on every signal
     */
    protected AbstractSignalSourceBuilder(@Nullable Class<T> messageType) {
        this.messageType = messageType;
    }

    @Override
    public SELF when(Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        requireOpen();
        sharedStages = true;
        this.when = predicate;
        return self();
    }

    @Override
    public SELF scope(Function<SignalMessage<T>, Optional<String>> scope) {
        Objects.requireNonNull(scope, "scope");
        requireOpen();
        sharedStages = true;
        this.scope = scope;
        return self();
    }

    @Override
    public SELF anyScope() {
        requireOpen();
        sharedStages = true;
        this.anyScope = true;
        return self();
    }

    @Override
    public SELF dedupBy(Function<T, String> dedupKey) {
        Objects.requireNonNull(dedupKey, "dedupKey");
        requireOpen();
        this.dedupBy = dedupKey;
        return self();
    }

    @Override
    public SELF lookupTimeoutMs(long timeoutMs) {
        requireOpen();
        sharedStages = true;
        this.lookupTimeoutMs = timeoutMs;
        return self();
    }

    @Override
    public SELF dispatchAs(Function<SignalMessage<T>, Future<Optional<SecurityIdentity>>> identity) {
        Objects.requireNonNull(identity, "identity");
        requireOpen();
        sharedStages = true;
        this.dispatchAs = identity;
        return self();
    }

    @Override
    public SELF restoreCorrelationFromHeaders() {
        requireOpen();
        this.restoreCorrelationFromHeaders = true;
        return self();
    }

    @Override
    public SELF onNoMatch(SignalNoMatch policy) {
        Objects.requireNonNull(policy, "policy");
        requireOpen();
        sharedStages = true;
        this.sourcePolicy = policy;
        return self();
    }

    @Override
    public SignalTargetStage<T, SELF, R> signal(Class<?> contract, String signalName) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(signalName, "signalName");
        requireOpen();
        sharedStages = true;
        Draft<T> draft = new Draft<>(contract, signalName);
        drafts.add(draft);
        return new TargetStage(draft);
    }

    /**
     * Checks the construction rules and returns the plan. Calling it again returns the same instance.
     *
     * @param sourceName the name of the source, which every failure and every event names
     * @return the plan
     * @throws IllegalStateException if the source breaks a construction rule
     */
    protected final SignalSourcePlan<T> plan(String sourceName) {
        Objects.requireNonNull(sourceName, "sourceName");
        SignalSourcePlan<T> existing = built;
        if (existing != null) {
            return existing;
        }
        if (drafts.isEmpty()) {
            throw refusal(sourceName, "declares no signal");
        }
        if (scope != null && anyScope) {
            throw refusal(sourceName, "names both scope and anyScope; choose one");
        }
        if (lookupTimeoutMs < SignalSourcePlan.MIN_LOOKUP_TIMEOUT_MS) {
            throw refusal(
                    sourceName,
                    "sets lookupTimeoutMs to " + lookupTimeoutMs + "; it must be at least "
                            + SignalSourcePlan.MIN_LOOKUP_TIMEOUT_MS);
        }
        SignalNoMatch fallback = sourcePolicy == null ? SignalNoMatch.IGNORE : sourcePolicy;
        List<Target<T>> targets = new ArrayList<>(drafts.size());
        for (Draft<T> draft : drafts) {
            targets.add(draft.target(sourceName, fallback, messageType, dedupBy != null));
        }
        SignalSourcePlan<T> plan = new SignalSourcePlan<>(
                sourceName, when, scope, dedupBy, lookupTimeoutMs, dispatchAs, restoreCorrelationFromHeaders, targets);
        built = plan;
        return plan;
    }

    /**
     * Tells whether any shared stage was set: {@code when}, {@code scope}, {@code anyScope},
     * {@code dispatchAs}, {@code onNoMatch} or {@code lookupTimeoutMs}, or a {@code signal}
     * target. A transport whose own shorthand cannot carry these stages uses it to refuse a mix
     * instead of dropping them.
     *
     * <p>{@code dedupBy} and {@code restoreCorrelationFromHeaders} do not count, because a
     * transport's older form takes them too.
     *
     * @return {@code true} when a shared stage or a signal target was set
     */
    protected final boolean hasSharedStages() {
        return sharedStages;
    }

    @SuppressWarnings("unchecked")
    private SELF self() {
        return (SELF) this;
    }

    private void requireOpen() {
        SignalSourcePlan<T> plan = built;
        if (plan != null) {
            throw new IllegalStateException(
                    "source '" + plan.sourceName() + "' is already built; a stage cannot follow build()");
        }
    }

    private static IllegalStateException refusal(String sourceName, String reason) {
        return new IllegalStateException("source '" + sourceName + "' " + reason);
    }

    /** The signal a builder is describing, until the plan snapshots it. */
    private static final class Draft<T> {
        private final Class<?> contract;
        private final String signalName;
        private boolean keyed;
        private boolean businessKey;
        private @Nullable Function<T, String> key;
        private @Nullable Function<SignalMessage<T>, Future<Optional<String>>> lookup;
        private @Nullable Function<T, ?> payload;
        private @Nullable SignalNoMatch policy;

        Draft(Class<?> contract, String signalName) {
            this.contract = contract;
            this.signalName = signalName;
        }

        void keySource(
                boolean businessKey,
                @Nullable Function<T, String> key,
                @Nullable Function<SignalMessage<T>, Future<Optional<String>>> lookup) {
            if (keyed) {
                throw new IllegalStateException("signal '" + signalName + "' already has a key source");
            }
            this.keyed = true;
            this.businessKey = businessKey;
            this.key = key;
            this.lookup = lookup;
        }

        Target<T> target(String sourceName, SignalNoMatch fallback, @Nullable Class<?> messageType, boolean dedup) {
            String where = "signal '" + signalName + "'";
            if (!keyed) {
                throw refusal(
                        sourceName,
                        where + " has no key source; follow it with key, keyFrom, businessKey or" + " businessKeyFrom");
            }
            if (businessKey && !dedup) {
                throw refusal(sourceName, where + " addresses a business key, which needs a dedupBy");
            }
            Class<?> parameterType = declaredPayloadType(sourceName, where);
            if (payload == null) {
                if (messageType == null) {
                    throw refusal(
                            sourceName, where + " needs a payload mapping because the source states no message type");
                }
                if (parameterType != null && !messageType.equals(parameterType)) {
                    throw refusal(
                            sourceName,
                            where + " takes " + parameterType.getSimpleName() + " but the message type is "
                                    + messageType.getSimpleName() + "; add a payload mapping");
                }
            }
            return new Target<>(
                    contract,
                    TYPE_NAMES.contractNameOf(contract),
                    signalName,
                    businessKey,
                    key,
                    lookup,
                    payload,
                    policy == null ? fallback : policy);
        }

        /** The type of the signal's payload parameter, read the way the proxy validator reads it. */
        private @Nullable Class<?> declaredPayloadType(String sourceName, String where) {
            for (Method method : contract.getMethods()) {
                WorkflowSignal declared = method.getAnnotation(WorkflowSignal.class);
                if (declared == null || !declared.value().equals(signalName)) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (parameterTypes[i] != WorkflowInstanceId.class
                            && !method.getParameters()[i].isAnnotationPresent(SignalDedupKey.class)) {
                        return parameterTypes[i];
                    }
                }
                return null;
            }
            throw refusal(sourceName, where + " is not declared by contract " + contract.getSimpleName());
        }
    }

    /** Offers the key sources of one signal and returns the stage that follows. */
    private final class TargetStage implements SignalTargetStage<T, SELF, R> {
        private final Draft<T> draft;

        TargetStage(Draft<T> draft) {
            this.draft = draft;
        }

        @Override
        public KeyedTargetStage<T, SELF, R> key(Function<T, String> key) {
            Objects.requireNonNull(key, "key");
            return keyed(false, key, null);
        }

        @Override
        public KeyedTargetStage<T, SELF, R> keyFrom(Function<SignalMessage<T>, Future<Optional<String>>> key) {
            Objects.requireNonNull(key, "key");
            return keyed(false, null, key);
        }

        @Override
        public KeyedTargetStage<T, SELF, R> businessKey(Function<T, String> key) {
            Objects.requireNonNull(key, "key");
            return keyed(true, key, null);
        }

        @Override
        public KeyedTargetStage<T, SELF, R> businessKeyFrom(Function<SignalMessage<T>, Future<Optional<String>>> key) {
            Objects.requireNonNull(key, "key");
            return keyed(true, null, key);
        }

        private KeyedTargetStage<T, SELF, R> keyed(
                boolean businessKey,
                @Nullable Function<T, String> key,
                @Nullable Function<SignalMessage<T>, Future<Optional<String>>> lookup) {
            requireOpen();
            draft.keySource(businessKey, key, lookup);
            return new KeyedStage(draft);
        }
    }

    /** What follows a key source: this signal's payload and policy, and every shared stage of the builder. */
    private final class KeyedStage implements KeyedTargetStage<T, SELF, R> {
        private final Draft<T> draft;

        KeyedStage(Draft<T> draft) {
            this.draft = draft;
        }

        @Override
        public KeyedTargetStage<T, SELF, R> payload(Function<T, ?> payload) {
            Objects.requireNonNull(payload, "payload");
            requireOpen();
            draft.payload = payload;
            return this;
        }

        @Override
        public KeyedTargetStage<T, SELF, R> onNoMatch(SignalNoMatch policy) {
            Objects.requireNonNull(policy, "policy");
            requireOpen();
            draft.policy = policy;
            return this;
        }

        @Override
        public SELF when(Predicate<T> predicate) {
            return AbstractSignalSourceBuilder.this.when(predicate);
        }

        @Override
        public SELF scope(Function<SignalMessage<T>, Optional<String>> scope) {
            return AbstractSignalSourceBuilder.this.scope(scope);
        }

        @Override
        public SELF anyScope() {
            return AbstractSignalSourceBuilder.this.anyScope();
        }

        @Override
        public SELF dedupBy(Function<T, String> dedupKey) {
            return AbstractSignalSourceBuilder.this.dedupBy(dedupKey);
        }

        @Override
        public SELF lookupTimeoutMs(long timeoutMs) {
            return AbstractSignalSourceBuilder.this.lookupTimeoutMs(timeoutMs);
        }

        @Override
        public SELF dispatchAs(Function<SignalMessage<T>, Future<Optional<SecurityIdentity>>> identity) {
            return AbstractSignalSourceBuilder.this.dispatchAs(identity);
        }

        @Override
        public SELF restoreCorrelationFromHeaders() {
            return AbstractSignalSourceBuilder.this.restoreCorrelationFromHeaders();
        }

        @Override
        public SignalTargetStage<T, SELF, R> signal(Class<?> contract, String signalName) {
            return AbstractSignalSourceBuilder.this.signal(contract, signalName);
        }

        @Override
        public R build() {
            return AbstractSignalSourceBuilder.this.build();
        }
    }
}
