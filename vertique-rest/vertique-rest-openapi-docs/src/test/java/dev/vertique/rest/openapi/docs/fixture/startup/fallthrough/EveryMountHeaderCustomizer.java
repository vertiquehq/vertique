// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.fallthrough;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.MountMeta;
import io.vertx.ext.web.Router;

/**
 * A {@link MountCustomizer} with the default {@code matches} (every mount) that adds to each router
 * it customizes a pass-through route at order {@link Integer#MIN_VALUE}, before the router's own
 * routes, setting {@value #HEADER} to the mount's id and calling {@code next()}.
 */
public final class EveryMountHeaderCustomizer implements MountCustomizer {

    /** The response header the added route sets. */
    public static final String HEADER = "X-Customized";

    /** Creates the customizer. */
    public EveryMountHeaderCustomizer() {}

    @Override
    public void customize(Router mountRouter, MountMeta meta) {
        String mountId = meta.mountId();
        mountRouter.route().order(Integer.MIN_VALUE).handler(ctx -> {
            ctx.response().putHeader(HEADER, mountId);
            ctx.next();
        });
    }

    /** Contributes an {@link EveryMountHeaderCustomizer} into {@code Set<MountCustomizer>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Contributes the customizer.
         *
         * @return a new customizer
         */
        @Provides
        @IntoSet
        static MountCustomizer everyMountHeaderCustomizer() {
            return new EveryMountHeaderCustomizer();
        }
    }
}
