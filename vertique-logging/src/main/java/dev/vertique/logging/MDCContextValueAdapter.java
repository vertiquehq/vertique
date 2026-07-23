// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import dev.vertique.context.ContextSnapshot;
import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextValueAdapter;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ContextValueAdapter} for {@link MDCContext}.
 *
 * <p>Discovered at substrate bootstrap via Java {@link java.util.ServiceLoader}; has a public no-arg
 * constructor and no Dagger dependencies so it can be instantiated before the Dagger graph exists.
 *
 * <p>Snapshot stores the live MDC entries as an immutable {@code Map<String, String>} under the
 * {@code MDCContext.class.getName()} key. Restore materialises a fresh {@link MDCContext} from
 * that frozen map. Duplicate (Vert.x context {@code duplicate(true)}) creates a fresh independent
 * {@link MDCContext} from the live entries so mutations on the duplicate do not bleed into the
 * source context.
 *
 * <p>Registered via {@code META-INF/services/dev.vertique.core.context.ContextValueAdapter} in
 * {@code vertique-logging}.
 */
public final class MDCContextValueAdapter implements ContextValueAdapter<MDCContext> {

    /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
    public MDCContextValueAdapter() {}

    /**
     * Returns the holder value type this adapter handles.
     *
     * @return {@link MDCContext}{@code .class}; never {@code null}
     */
    @Override
    public Class<MDCContext> type() {
        return MDCContext.class;
    }

    /**
     * Snapshots the live {@link MDCContext} into an immutable {@code Map<String, String>} for
     * {@link ContextValues#snapshot()}.
     *
     * @param live the live context value; must not be {@code null}
     * @return an immutable copy of the live MDC entries; never {@code null}
     */
    @Override
    public Object snapshot(MDCContext live) {
        Objects.requireNonNull(live, "live must not be null");
        return live.mapView();
    }

    /**
     * Rebuilds a fresh {@link MDCContext} from the snapshot for
     * {@link ContextValues#bindSnapshot(ContextSnapshot)}.
     *
     * @param snapshot the previously produced snapshot; must be a {@code Map<String, String>};
     *                 must not be {@code null}
     * @return a fresh {@link MDCContext} populated with the snapshot entries; never {@code null}
     * @throws IllegalArgumentException if {@code snapshot} is not a {@code Map}
     */
    @Override
    public MDCContext restoreFromSnapshot(Object snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!(snapshot instanceof Map<?, ?> frozen)) {
            throw new IllegalArgumentException("Expected MDC snapshot Map<String,String> but got "
                    + snapshot.getClass().getName());
        }
        @SuppressWarnings("unchecked")
        Map<String, String> typed = (Map<String, String>) frozen;
        return new MDCContext(typed);
    }

    /**
     * Deep-copies the live {@link MDCContext} for use on a Vert.x context {@code duplicate(true)}.
     * Creates a fresh independent instance so mutations on the duplicate do not bleed into the
     * source context.
     *
     * @param live the live context value; must not be {@code null}
     * @return a fresh {@link MDCContext} with the same entries as {@code live}; never {@code null}
     */
    @Override
    public MDCContext duplicate(MDCContext live) {
        Objects.requireNonNull(live, "live must not be null");
        return new MDCContext(live.mapView());
    }
}
