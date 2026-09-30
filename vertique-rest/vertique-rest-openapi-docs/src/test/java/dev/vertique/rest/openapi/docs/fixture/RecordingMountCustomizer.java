// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.MountMeta;
import io.vertx.ext.web.Router;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link MountCustomizer} with the default {@code matches} (every mount) that records, in order,
 * the {@link MountMeta} of every mount it is applied to, and changes nothing.
 */
public final class RecordingMountCustomizer implements MountCustomizer {

    private final List<MountMeta> applied = new CopyOnWriteArrayList<>();

    /** Creates a customizer with no recorded application. */
    public RecordingMountCustomizer() {}

    @Override
    public void customize(Router mountRouter, MountMeta meta) {
        applied.add(meta);
    }

    /**
     * Returns every recorded meta, in application order.
     *
     * @return an immutable snapshot of the recorded metas
     */
    public List<MountMeta> applied() {
        return List.copyOf(applied);
    }

    /**
     * Returns every recorded mount path, in application order.
     *
     * @return the recorded mount paths
     */
    public List<String> mountPaths() {
        return applied.stream().map(MountMeta::mountPath).toList();
    }

    /** Forgets every recorded meta. */
    public void clear() {
        applied.clear();
    }

    /** Binds one component-scoped {@link RecordingMountCustomizer} into {@code Set<MountCustomizer>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Provides the component's recording customizer.
         *
         * @return a new customizer
         */
        @Provides
        @Singleton
        static RecordingMountCustomizer recordingMountCustomizer() {
            return new RecordingMountCustomizer();
        }

        /**
         * Contributes the recording customizer.
         *
         * @param customizer the component's recording customizer
         * @return {@code customizer}
         */
        @Provides
        @IntoSet
        static MountCustomizer asMountCustomizer(RecordingMountCustomizer customizer) {
            return customizer;
        }
    }
}
