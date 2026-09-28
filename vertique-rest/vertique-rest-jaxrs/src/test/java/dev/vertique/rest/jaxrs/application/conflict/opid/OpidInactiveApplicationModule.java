// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Case (e)'s registration module for {@link OpidApis.OpidInactiveApi}, unconditionally inactive.
 * The sole module contributing to {@code Set<GeneratedRestApplicationRegistration>} in case (e)'s
 * component, so the view holds exactly one entry: a registration whose condition does not match
 * (AC-014.1). With no active application, this registration alone still makes the registration set
 * non-empty, so the cross-mount operationId scan runs over {@link OpidHandBuiltOneMountModule}'s
 * and {@link OpidHandBuiltTwoMountModule}'s mounts even though neither is an application mount.
 */
@Module
public final class OpidInactiveApplicationModule {

    private OpidInactiveApplicationModule() {}

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration opidInactiveApplicationRegistration() {
        return GeneratedRestApplicationRegistration.of(
                OpidApis.OpidInactiveApi.class,
                "opid-inactive",
                "/opid/inactive",
                List.of(OpidInactiveResource.class),
                false,
                "",
                false);
    }
}
