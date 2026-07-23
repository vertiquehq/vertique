// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.extension;

import java.util.Comparator;

/**
 * Mix-in for framework extensions that participate in a deterministic ordering contract.
 *
 * <p>Extensions are ordered by {@link #phase()} first (coarse, system-vs-application), then
 * ascending {@link #priority()} (finer, within a phase), then {@link #orderKey()} (a stable
 * tie-break) — see {@link #comparator()}.
 *
 * <p>Application extensions default to {@link ExtensionPhase#APPLICATION}; only system/platform-owned
 * extensions (Vertique modules or trusted application-platform modules) should override the phase to
 * {@code SYSTEM_FIRST}/{@code SYSTEM_LAST}. The phase is a trusted ordering hint, not a security boundary.
 */
public interface OrderedExtension {

    /**
     * Returns the coarse ordering phase for this extension.
     *
     * @return the phase; defaults to {@link ExtensionPhase#APPLICATION}
     */
    default ExtensionPhase phase() {
        return ExtensionPhase.APPLICATION;
    }

    /**
     * Returns the fine ordering priority within a phase. Lower values run first.
     *
     * @return the priority; defaults to {@code 0}
     */
    default int priority() {
        return 0;
    }

    /**
     * Returns a stable tie-break key used when two extensions share the same phase and priority.
     * Implementations MUST return a non-null value, and SHOULD return one unique per registered
     * instance to keep ordering fully deterministic — the default (the fully-qualified class name) is
     * unique per class but ties for two instances of the same class, so an extension registered more
     * than once should override this with a distinct key.
     *
     * <p><b>Lambda / method-reference registrations</b> get a synthetic, JVM-generated class name
     * (e.g. {@code ...$$Lambda$1234}) that is <em>not</em> stable across compilations or runs. Two
     * lambda-registered extensions with equal phase and priority therefore have no meaningful
     * source-level tie order. When relative order between such extensions matters, give them distinct
     * {@link #priority()} values, register them as named classes, or override this method.
     *
     * @return a stable, non-null string key; defaults to the fully-qualified implementation class name
     */
    default String orderKey() {
        return getClass().getName();
    }

    /**
     * Returns the canonical ordering comparator for {@link OrderedExtension} instances.
     *
     * <p>Ordering is: {@link #phase()} ascending (declaration order), then {@link #priority()}
     * ascending, then {@link #orderKey()} ascending. The result is fully deterministic when each
     * extension's {@link #orderKey()} is unique (see that method's contract); ties on an identical
     * {@code (phase, priority, orderKey)} triple sort in an unspecified but stable-per-run order.
     *
     * @return the ordering comparator
     */
    static Comparator<OrderedExtension> comparator() {
        return Comparator.comparing(OrderedExtension::phase)
                .thenComparingInt(OrderedExtension::priority)
                .thenComparing(OrderedExtension::orderKey);
    }
}
