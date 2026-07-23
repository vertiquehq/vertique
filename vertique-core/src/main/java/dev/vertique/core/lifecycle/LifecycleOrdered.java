// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import java.util.Comparator;

/**
 * Shared ordering contract for application-lifecycle participants.
 *
 * <p>Participants are ordered by {@link #phase()} first ({@link LifecyclePhase} natural/ordinal
 * order), then ascending {@link #priority()} (finer, within a phase), then {@link #orderKey()} (a
 * stable tie-break) — see {@link #comparator()}.
 *
 * <p>This is the lifecycle-specific ordering contract and is deliberately <em>not</em> related to
 * {@link dev.vertique.core.extension.OrderedExtension}: that interface's {@code phase()} returns an
 * {@link dev.vertique.core.extension.ExtensionPhase}, a different enum, so extending it here would
 * clash on the {@code phase()} return type. {@code LifecycleOrdered} keys its ordering on
 * {@link LifecyclePhase} instead.
 */
public interface LifecycleOrdered {

    /**
     * Returns the lifecycle phase this participant belongs to.
     *
     * @return the phase; never {@code null}
     */
    LifecyclePhase phase();

    /**
     * Returns the fine ordering priority within a phase. Lower values run first.
     *
     * @return the priority; defaults to {@code 0}
     */
    default int priority() {
        return 0;
    }

    /**
     * Returns a stable tie-break key used when two participants share the same phase and priority.
     * Implementations MUST return a non-null value, and SHOULD return one unique per registered
     * instance to keep ordering fully deterministic — the default (the fully-qualified class name) is
     * unique per class but ties for two instances of the same class, so a participant registered more
     * than once should override this with a distinct key.
     *
     * <p><b>Lambda / method-reference registrations</b> get a synthetic, JVM-generated class name
     * (e.g. {@code ...$$Lambda$1234}) that is <em>not</em> stable across compilations or runs. Two
     * lambda-registered participants with equal phase and priority therefore have no meaningful
     * source-level tie order. When relative order between such participants matters, give them
     * distinct {@link #priority()} values, register them as named classes, or override this method.
     *
     * @return a stable, non-null string key; defaults to the fully-qualified implementation class
     *     name
     */
    default String orderKey() {
        return getClass().getName();
    }

    /**
     * Returns the canonical ordering comparator for {@link LifecycleOrdered} instances.
     *
     * <p>Ordering is: {@link #phase()} ascending ({@link LifecyclePhase} declaration order), then
     * {@link #priority()} ascending, then {@link #orderKey()} ascending. The result is fully
     * deterministic when each participant's {@link #orderKey()} is unique (see that method's
     * contract); ties on an identical {@code (phase, priority, orderKey)} triple sort in an
     * unspecified but stable-per-run order.
     *
     * @return the ordering comparator
     */
    static Comparator<LifecycleOrdered> comparator() {
        return Comparator.comparing(LifecycleOrdered::phase)
                .thenComparingInt(LifecycleOrdered::priority)
                .thenComparing(LifecycleOrdered::orderKey);
    }
}
