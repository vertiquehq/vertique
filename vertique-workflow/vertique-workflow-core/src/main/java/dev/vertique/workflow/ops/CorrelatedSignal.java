// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import dev.vertique.workflow.types.ScopeKeyRules;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * A signal addressed to the instance that waits on a key, not to an instance id.
 *
 * <p>The key is matched against the key a waiting instance computed when it entered its wait. The delivery looks for
 * one open wait of the contract, the signal name and the key, across definitions and scopes unless
 * {@link #withDefinition} or {@link #withScope} narrows it.
 *
 * <p>A key is a non-blank string of at most {@value #MAX_KEY_LENGTH} characters with no NUL character. A dedup key is
 * non-blank. A refusal names the component and never repeats the value.
 *
 * @param contract the workflow contract the signal is addressed to
 * @param signalName the name of the signal to deliver
 * @param key the key the waiting instance computed
 * @param payload the signal payload, or {@code null} for none
 * @param dedupKey the caller's idempotency key for the delivery
 * @param definitionId the definition the waiting instance runs, or {@code null} for any
 * @param scopeKey the scope of the waiting instance, or {@code null} for any
 * @param provenance where the signal came from, or {@code null} when it is not recorded
 */
public record CorrelatedSignal(
        Class<?> contract,
        String signalName,
        String key,
        @Nullable Object payload,
        String dedupKey,
        @Nullable String definitionId,
        @Nullable String scopeKey,
        @Nullable SignalProvenance provenance) {

    /** The most characters a key may hold. */
    public static final int MAX_KEY_LENGTH = 255;

    /**
     * Validates the components.
     *
     * @throws NullPointerException if the contract, signal name, key or dedup key is {@code null}
     * @throws IllegalArgumentException if the key is blank, longer than {@value #MAX_KEY_LENGTH} characters or holds a
     *     NUL character, if the dedup key is blank, or if the scope key breaks the scope key rules
     */
    public CorrelatedSignal {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(signalName, "signalName");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(dedupKey, "dedupKey");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        if (key.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("key must not contain a NUL character");
        }
        if (dedupKey.isBlank()) {
            throw new IllegalArgumentException("dedupKey must not be blank");
        }
        if (scopeKey != null) {
            ScopeKeyRules.require(scopeKey, "scopeKey");
        }
    }

    /**
     * Makes a signal that matches any definition and any scope and carries no provenance.
     *
     * @param contract the workflow contract the signal is addressed to
     * @param signalName the name of the signal to deliver
     * @param key the key the waiting instance computed
     * @param payload the signal payload, or {@code null} for none
     * @param dedupKey the caller's idempotency key for the delivery
     * @return the signal
     */
    public static CorrelatedSignal of(
            Class<?> contract, String signalName, String key, @Nullable Object payload, String dedupKey) {
        return new CorrelatedSignal(contract, signalName, key, payload, dedupKey, null, null, null);
    }

    /**
     * Narrows the match to the instances that run one definition.
     *
     * @param definitionId the id of the definition
     * @return a copy that differs only in its definition id
     */
    public CorrelatedSignal withDefinition(String definitionId) {
        return new CorrelatedSignal(
                contract,
                signalName,
                key,
                payload,
                dedupKey,
                Objects.requireNonNull(definitionId, "definitionId"),
                scopeKey,
                provenance);
    }

    /**
     * Narrows the match to the instances of one scope.
     *
     * @param scopeKey the scope key; checked by the scope key rules
     * @return a copy that differs only in its scope key
     * @throws IllegalArgumentException if the scope key breaks the scope key rules
     */
    public CorrelatedSignal withScope(String scopeKey) {
        ScopeKeyRules.require(scopeKey, "scopeKey");
        return new CorrelatedSignal(contract, signalName, key, payload, dedupKey, definitionId, scopeKey, provenance);
    }

    /**
     * Records where the signal came from.
     *
     * @param provenance the provenance
     * @return a copy that differs only in its provenance
     */
    public CorrelatedSignal withProvenance(SignalProvenance provenance) {
        return new CorrelatedSignal(
                contract,
                signalName,
                key,
                payload,
                dedupKey,
                definitionId,
                scopeKey,
                Objects.requireNonNull(provenance, "provenance"));
    }

    /**
     * Describes the signal by contract and signal name only, so that a log line never carries the key, the dedup key
     * or the payload.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "CorrelatedSignal[contract=" + contract.getName() + ", signalName=" + signalName + "]";
    }
}
