// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Case (f)'s registration module for {@link OpidApis.OpidAopApi}, unconditionally active.
 */
@Module
public final class OpidAopApplicationRegistrationModule {

    private OpidAopApplicationRegistrationModule() {}

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration opidAopApplicationRegistration() {
        return GeneratedRestApplicationRegistration.of(
                OpidApis.OpidAopApi.class,
                "opid-aop",
                "/opid/aop",
                List.of(OpidAopBaseResource.class),
                false,
                "",
                true);
    }
}
