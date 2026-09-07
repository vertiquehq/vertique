// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdGenerator;
import dev.vertique.core.correlation.CorrelationIdentifier;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Optional;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications use the surface the module document lists and the
 * types in {@code dev.vertique.core}.
 *
 * <p>Framework write-surface for creating initial {@link CorrelationContext} instances.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code CorrelationIngressMiddleware} at REST ingress — {@link #create}.</li>
 *   <li>{@code CorrelationContextSeeder} for first-ingress seeding on non-REST surfaces
 *       (service dispatch, Kafka, outbox relay, delayed-job, workflow branch) — {@link #seed}.</li>
 *   <li>The service-dispatch + durable decoders to rebuild a fresh live context from an
 *       incoming snapshot — {@link #fromSnapshot}.</li>
 * </ul>
 *
 * <p>Applications never see this factory at read time — handlers obtain the bound context via
 * {@code ContextHolder.current(CorrelationContext.class)} or
 * {@code ContextValues.current(CorrelationContext.class)}.
 *
 * <p>The injected {@code Optional<CorrelationIdGenerator>} resolves through the
 * {@code @BindsOptionalOf CorrelationIdGenerator} declared in {@code CorrelationContextModule},
 * so an app-supplied generator overrides the default {@link Uuid4CorrelationIdGenerator}
 * without ambiguity.
 */
@Singleton
public final class CorrelationContextFactory {

    private final CorrelationIdGenerator generator;

    /**
     * @param override an optional application-supplied {@link CorrelationIdGenerator} binding;
     *                 when empty, the framework default ({@link Uuid4CorrelationIdGenerator}) is used
     */
    @Inject
    public CorrelationContextFactory(Optional<CorrelationIdGenerator> override) {
        this.generator = override.orElse(Uuid4CorrelationIdGenerator.INSTANCE);
    }

    // --- Factory entry points ---

    /**
     * Builds a fresh live context with the given identifiers. Other fields are left
     * null/empty until enricher mutations land.
     *
     * @param requestId     the request id; must not be null
     * @param correlationId the correlation id; must not be null
     * @return a fresh {@link CorrelationContext}
     */
    public CorrelationContext create(CorrelationIdentifier requestId, CorrelationIdentifier correlationId) {
        return new MutableCorrelationContext(
                Objects.requireNonNull(requestId, "requestId"), Objects.requireNonNull(correlationId, "correlationId"));
    }

    /**
     * Seeds a fresh live context for first-ingress contributors. Both ids are minted from the
     * configured {@link CorrelationIdGenerator} and tagged with the boundary as their
     * {@link CorrelationIdentifier#source()}. Used by the {@code CorrelationContextSeeder}
     * registered as an {@code InboundContextInitializer}.
     *
     * @param boundary a short label identifying the inbound boundary (e.g. {@code "kafka"},
     *                 {@code "outbox-service"}, {@code "delayed-job"}, {@code "workflow-branch"});
     *                 must not be null
     * @return a fresh {@link CorrelationContext} whose ids carry {@code source = "seeded:" + boundary}
     */
    public CorrelationContext seed(String boundary) {
        Objects.requireNonNull(boundary, "boundary");
        String source = "seeded:" + boundary;
        CorrelationIdentifier requestId = new CorrelationIdentifier(generator.generate(), source);
        CorrelationIdentifier correlationId = new CorrelationIdentifier(generator.generate(), source);
        return new MutableCorrelationContext(requestId, correlationId);
    }

    /**
     * Rebuilds a fresh live context from a decoded snapshot. Used by service-dispatch and
     * durable decoders on the receiving side of a dispatch boundary.
     *
     * @param snapshot the snapshot to materialise; must not be null
     * @return a fresh live {@link CorrelationContext} whose {@code snapshot()} equals
     *         {@code snapshot}
     */
    public CorrelationContext fromSnapshot(CorrelationContextSnapshot snapshot) {
        return MutableCorrelationContext.fromSnapshot(snapshot);
    }

    /**
     * Exposes the effective generator (override or default). Primarily for diagnostics and
     * tests that need to assert which generator the factory is using.
     *
     * @return the active generator; never null
     */
    public CorrelationIdGenerator generator() {
        return generator;
    }
}
