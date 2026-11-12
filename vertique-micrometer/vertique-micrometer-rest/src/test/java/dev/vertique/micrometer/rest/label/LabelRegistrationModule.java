// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import jakarta.inject.Provider;
import java.util.List;

/**
 * Hand-written module in the shape the annotation processor emits for {@link LabelApplications}:
 * one always-active registration per declaration and one catalog entry per listed resource, so the
 * composer can select {@link SharedItemsResource} for both {@code public} and {@code partner} and
 * {@link ManagementResource} for {@code mgmt}. No {@code @JaxRsResources} binding exists: with
 * registrations present, the composition is in explicit mode and mounts only the applications.
 */
@Module
final class LabelRegistrationModule {

    private LabelRegistrationModule() {}

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration publicRegistration() {
        return GeneratedRestApplicationRegistration.of(
                LabelApplications.PublicApi.class,
                "public",
                "/api/public",
                List.of(SharedItemsResource.class),
                false,
                "",
                true);
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration partnerRegistration() {
        return GeneratedRestApplicationRegistration.of(
                LabelApplications.PartnerApi.class,
                "partner",
                "/api/partner",
                List.of(SharedItemsResource.class),
                false,
                "",
                true);
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration managementRegistration() {
        return GeneratedRestApplicationRegistration.of(
                LabelApplications.ManagementApi.class,
                "mgmt",
                "/api/mgmt",
                List.of(ManagementResource.class),
                false,
                "",
                true);
    }

    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry sharedItemsEntry(Provider<SharedItemsResource> provider) {
        return GeneratedJaxRsResourceEntry.of(SharedItemsResource.class, true, provider);
    }

    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry managementEntry(Provider<ManagementResource> provider) {
        return GeneratedJaxRsResourceEntry.of(ManagementResource.class, true, provider);
    }
}
