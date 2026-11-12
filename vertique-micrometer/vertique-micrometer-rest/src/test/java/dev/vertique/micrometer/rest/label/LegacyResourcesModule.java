// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import java.util.Set;

/**
 * The zero-declaration composition's resources: {@link LegacyResource} contributed to
 * {@code @JaxRsResources}, so the default mount at {@code /*} serves it outside any application.
 */
@Module
final class LegacyResourcesModule {

    private LegacyResourcesModule() {}

    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> legacyResources(LegacyResource resource) {
        return Set.of(resource);
    }
}
