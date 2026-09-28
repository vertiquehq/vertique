// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy.app;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.application.policy.OrderMismatchResource;
import dev.vertique.rest.jaxrs.application.policy.PermitAllResource;
import dev.vertique.rest.jaxrs.application.policy.PermitAllScopedResource;
import dev.vertique.rest.jaxrs.application.policy.RequiresActionResource;
import dev.vertique.rest.jaxrs.application.policy.ScopelessRequirementResource;
import dev.vertique.rest.jaxrs.application.policy.UnannotatedResource;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Hand-written module in the exact C-GEN shape for compilation unit {@code policy.app} (T005,
 * ported to native registrations at T023 L22): registers {@link ManagementApis}'s six declaring
 * interfaces, each gated on the same {@code policy.<variant>.enabled} property that
 * {@code policy.GeneratedJaxRsResourcesModule} already uses to gate that variant's catalog entry.
 * Exactly one is active per test invocation, so the application-mount vs. legacy-default-mount
 * choice is still made by which {@code ExplicitPolicyComponents} component a test builds (this
 * module is absent from {@code LegacyDefaultMountComponent}), and which variant's mount is composed
 * is made by which single {@code policy.<variant>.enabled} property a test sets — replacing the
 * former {@code policy.app.classes} selection, now unnecessary since each variant is its own
 * registration.
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    private GeneratedJaxRsResourcesModule() {}

    private static PropertyCondition[] conditions(String key) {
        return new PropertyCondition[] {new PropertyCondition(key, "true", false)};
    }

    private static final PropertyCondition[] UNANNOTATED = conditions("policy.unannotated.enabled");
    private static final PropertyCondition[] PERMIT_ALL = conditions("policy.permitAll.enabled");
    private static final PropertyCondition[] SCOPELESS = conditions("policy.scopeless.enabled");
    private static final PropertyCondition[] REQUIRES_ACTION = conditions("policy.requiresAction.enabled");
    private static final PropertyCondition[] PERMIT_ALL_SCOPED = conditions("policy.permitAllScoped.enabled");
    private static final PropertyCondition[] ORDER_MISMATCH = conditions("policy.orderMismatch.enabled");

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration unannotatedManagementRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                ManagementApis.UnannotatedApi.class,
                "mgmt-unannotated",
                "/api/mgmt",
                List.of(UnannotatedResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, UNANNOTATED));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration permitAllManagementRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                ManagementApis.PermitAllApi.class,
                "mgmt-permit-all",
                "/api/mgmt",
                List.of(PermitAllResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, PERMIT_ALL));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration scopelessManagementRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                ManagementApis.ScopelessApi.class,
                "mgmt-scopeless",
                "/api/mgmt",
                List.of(ScopelessRequirementResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SCOPELESS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration requiresActionManagementRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                ManagementApis.RequiresActionApi.class,
                "mgmt-requires-action",
                "/api/mgmt",
                List.of(RequiresActionResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, REQUIRES_ACTION));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration permitAllScopedManagementRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                ManagementApis.PermitAllScopedApi.class,
                "mgmt-permit-all-scoped",
                "/api/mgmt",
                List.of(PermitAllScopedResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, PERMIT_ALL_SCOPED));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration orderMismatchManagementRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                ManagementApis.OrderMismatchApi.class,
                "mgmt-order-mismatch",
                "/api/mgmt",
                List.of(OrderMismatchResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, ORDER_MISMATCH));
    }
}
