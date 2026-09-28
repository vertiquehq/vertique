// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.reentry;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.application.manual.ReentrantResource;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/** TP-015 (G-06 (b))'s registration module for {@link ReentrantExplicitApi}, unconditionally active. */
@Module
public final class ReentrantExplicitApplicationModule {

    private ReentrantExplicitApplicationModule() {}

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration reentrantExplicitApplicationRegistration() {
        return GeneratedRestApplicationRegistration.of(
                ReentrantExplicitApi.class,
                "reentrant-explicit",
                "/api/reentrant-explicit",
                List.of(ReentrantResource.class),
                false,
                "",
                true);
    }
}
