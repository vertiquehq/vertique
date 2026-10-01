// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import java.util.Set;

/**
 * One hand-built JAX-RS mount holding one case resource and no OpenAPI contract location; a
 * component receives it as a bound instance and {@link Contribution} builds the mount. The mount
 * belongs to no application.
 *
 * @param mountPath the literal mount path, such as {@code /*}
 * @param resource  the mount's only resource
 */
public record CaseMount(String mountPath, CaseResource resource) {

    /** Builds the bound {@link CaseMount} and contributes it into {@code Set<RouterMount>}. */
    @Module
    public static final class Contribution {

        private Contribution() {}

        /**
         * Builds the case mount with the JAX-RS mount factory's default priority.
         *
         * @param factory   the JAX-RS mount factory
         * @param caseMount the component's bound case mount
         * @return the mount
         */
        @Provides
        @IntoSet
        static RouterMount caseMount(JaxRsRouterMount.Factory factory, CaseMount caseMount) {
            return factory.create(caseMount.mountPath(), null, Set.of(caseMount.resource()));
        }
    }
}
