// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import dev.vertique.core.correlation.TraceReference;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Package-private mutable implementation of the public {@link CorrelationContext} interface.
 *
 * <p>Bound under the holder key {@code CorrelationContext.class.getName()} by
 * {@code CorrelationContextFactory.create(...)} at REST ingress (and again by the service- and
 * durable-dispatch decoders when the context arrives from another node). Framework code mutates
 * through {@code CorrelationContextMutator}; application handlers see only the read-only
 * interface returned by {@code ContextHolder.current(CorrelationContext.class)}.
 *
 * <p><b>Concurrency model — Vert.x duplicated-context confinement.</b> Each request/dispatch
 * runs on its own duplicated Vert.x context; the substrate's holder slot stores one
 * {@code MutableCorrelationContext} per duplicated context. All reads and writes happen on the
 * owning context's event-loop or worker thread, so plain fields plus {@link ArrayList}/{@link
 * HashMap} are sufficient — no synchronisation, no {@code volatile}, no copy-on-write
 * structures. This mirrors {@code MDCContext} and is enforced upstream by
 * {@code DefaultContextHolder.requireDuplicatedContextForWrite}.
 *
 * <p>{@link #snapshot()} takes defensive {@code List.copyOf}/{@code Map.copyOf} copies so the
 * resulting {@link CorrelationContextSnapshot} can cross threads safely (service-dispatch
 * encoders hand it to other Vert.x contexts; durable encoders serialise it into JSON).
 */
final class MutableCorrelationContext implements CorrelationContext {

    // --- Required fields ---

    private final CorrelationIdentifier requestId;
    private final CorrelationIdentifier correlationId;

    // --- Optional fields ---

    @Nullable
    private CorrelationIdentifier causationId;

    @Nullable
    private TraceReference trace;

    @Nullable
    private CorrelationSessionRef session;

    private final List<ProtocolCorrelationRef> protocolCorrelations = new ArrayList<>();
    private final Map<String, String> attributes = new HashMap<>();

    // --- Constructors ---

    /**
     * Constructs a fresh mutable context with the given required ids.
     *
     * @param requestId     the request-scoped identifier; must not be null
     * @param correlationId the correlation identifier; must not be null
     * @throws NullPointerException if either argument is null
     */
    MutableCorrelationContext(CorrelationIdentifier requestId, CorrelationIdentifier correlationId) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    // --- CorrelationContext (read API) ---

    @Override
    public CorrelationIdentifier requestId() {
        return requestId;
    }

    @Override
    public CorrelationIdentifier correlationId() {
        return correlationId;
    }

    @Override
    @Nullable
    public CorrelationIdentifier causationId() {
        return causationId;
    }

    @Override
    @Nullable
    public TraceReference trace() {
        return trace;
    }

    @Override
    public List<ProtocolCorrelationRef> protocolCorrelations() {
        return Collections.unmodifiableList(protocolCorrelations);
    }

    @Override
    @Nullable
    public CorrelationSessionRef session() {
        return session;
    }

    @Override
    public Map<String, String> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public CorrelationContextSnapshot snapshot() {
        return new CorrelationContextSnapshot(
                requestId,
                correlationId,
                causationId,
                trace,
                List.copyOf(protocolCorrelations),
                session,
                Map.copyOf(attributes));
    }

    // --- Package-private mutators (called only by CorrelationContextMutator / Factory) ---

    void setCausationId(@Nullable CorrelationIdentifier causationId) {
        this.causationId = causationId;
    }

    void setTrace(@Nullable TraceReference trace) {
        this.trace = trace;
    }

    void setSession(@Nullable CorrelationSessionRef session) {
        this.session = session;
    }

    void addProtocolCorrelation(ProtocolCorrelationRef ref) {
        Objects.requireNonNull(ref, "ref");
        protocolCorrelations.add(ref);
    }

    void putAttribute(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        attributes.put(key, value);
    }

    // --- Static factory (used by the ValueAdapter and the Factory) ---

    /**
     * Rebuilds a fresh mutable context from an immutable snapshot. Used by
     * {@code CorrelationContextValueAdapter.restoreFromSnapshot} (via
     * {@code ContextValues.bindSnapshot}) and {@code CorrelationContextFactory.fromSnapshot}.
     *
     * @param snapshot the snapshot to materialise; must not be null
     * @return a fresh mutable context whose {@link #snapshot()} equals {@code snapshot}
     * @throws NullPointerException if {@code snapshot} is null
     */
    static MutableCorrelationContext fromSnapshot(CorrelationContextSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        MutableCorrelationContext ctx = new MutableCorrelationContext(snapshot.requestId(), snapshot.correlationId());
        ctx.causationId = snapshot.causationId();
        ctx.trace = snapshot.trace();
        ctx.session = snapshot.session();
        ctx.protocolCorrelations.addAll(snapshot.protocolCorrelations());
        ctx.attributes.putAll(snapshot.attributes());
        return ctx;
    }
}
