// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * SPI for feature modules to declare per-value snapshot/restore/duplicate behavior so the
 * substrate can manage holder-bound values uniformly without naming features. Discovered via Java
 * {@link java.util.ServiceLoader} at bootstrap; implementations must be pure providers with a
 * public no-arg constructor and NO Dagger dependencies.
 *
 * <p>Scope of this SPI is local holder lifecycle: snapshot/restore for {@link ContextValues}, and
 * deep-copy on Vert.x context {@code duplicate(true)}. Boundary propagation (service-dispatch,
 * durable metadata) uses {@link ServiceDispatchContextEncoder}/{@link ServiceDispatchContextDecoder}
 * and {@link DurableContextMetadataEncoder}/{@link DurableContextMetadataDecoder}, not this
 * adapter.
 *
 * @param <T> the holder value type the adapter handles; must implement {@link ContextValue}
 */
public interface ContextValueAdapter<T extends ContextValue> {

    /**
     * Returns the holder value type this adapter handles.
     *
     * @return the handled value type; never {@code null}
     */
    Class<T> type();

    /**
     * Snapshots the live value into an immutable form for {@link ContextValues#snapshot()}.
     *
     * @param live the live context value; never {@code null}
     * @return an immutable snapshot representation; must not be {@code null}
     */
    Object snapshot(T live);

    /**
     * Rebuilds a fresh live value from the snapshot for {@link ContextValues#bindSnapshot(ContextSnapshot)}.
     *
     * @param snapshot the previously produced snapshot; never {@code null}
     * @return a fresh live value reconstructed from the snapshot; must not be {@code null}
     */
    T restoreFromSnapshot(Object snapshot);

    /**
     * Deep-copies the live value for use on a Vert.x context {@code duplicate(true)}. The default
     * implementation returns the live value unchanged, which is correct for immutable values.
     *
     * @param live the live context value; never {@code null}
     * @return a deep copy of the live value, or the original if immutable; must not be {@code null}
     */
    default T duplicate(T live) {
        return live;
    }
}
