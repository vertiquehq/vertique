// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import dev.vertique.core.context.ContextValueAdapter;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import java.util.Objects;

/**
 * {@link ContextValueAdapter} for {@link CorrelationContext}.
 *
 * <p>Discovered at substrate bootstrap via Java {@link java.util.ServiceLoader} (registered in
 * {@code META-INF/services/dev.vertique.core.context.ContextValueAdapter}) so the substrate can
 * find this adapter before the Dagger graph exists. Public no-arg constructor; no Dagger
 * dependencies; restore goes through the package-private
 * {@link MutableCorrelationContext#fromSnapshot} static helper rather than the {@code @Singleton}
 * {@link CorrelationContextFactory} for the same bootstrap-safety reason.
 *
 * <p><b>type() returns the public interface, not the impl.</b> The substrate holder slot is
 * keyed by {@code CorrelationContext.class.getName()} (the interface FQCN), so the adapter must
 * report {@code CorrelationContext.class} for {@code ContextLocalServiceProvider.adapterByFqcn}
 * to find it. Reporting {@code MutableCorrelationContext.class} would cause the substrate to
 * fall back to reference storage on snapshot/duplicate and break the snapshot/restore
 * round-trip for inherited context state.
 *
 * <p>Behaviour:
 * <ul>
 *   <li><b>snapshot</b>: returns the {@link CorrelationContext#snapshot()} of the live value —
 *       an immutable {@link CorrelationContextSnapshot}.</li>
 *   <li><b>restoreFromSnapshot</b>: materialises a fresh independent
 *       {@link MutableCorrelationContext} via {@link MutableCorrelationContext#fromSnapshot}.</li>
 *   <li><b>duplicate</b> (Vert.x context {@code duplicate(true)}): takes a snapshot of the live
 *       value and rebuilds a fresh independent context. Mutations on the duplicate do not bleed
 *       into the source context.</li>
 * </ul>
 */
public final class CorrelationContextValueAdapter implements ContextValueAdapter<CorrelationContext> {

    /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
    public CorrelationContextValueAdapter() {}

    @Override
    public Class<CorrelationContext> type() {
        return CorrelationContext.class;
    }

    @Override
    public Object snapshot(CorrelationContext live) {
        Objects.requireNonNull(live, "live must not be null");
        return live.snapshot();
    }

    @Override
    public CorrelationContext restoreFromSnapshot(Object snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!(snapshot instanceof CorrelationContextSnapshot frozen)) {
            throw new IllegalArgumentException("Expected CorrelationContextSnapshot but got "
                    + snapshot.getClass().getName());
        }
        return MutableCorrelationContext.fromSnapshot(frozen);
    }

    @Override
    public CorrelationContext duplicate(CorrelationContext live) {
        Objects.requireNonNull(live, "live must not be null");
        return MutableCorrelationContext.fromSnapshot(live.snapshot());
    }
}
