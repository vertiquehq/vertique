// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.DecisionLog;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.DenialRecovery;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.Effects;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.Eligibility;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.RecordingAuthorizer;
import dev.vertique.examples.services.codegen.policy.resource.GeneratedJaxRsResourcesModule;
import dev.vertique.examples.services.codegen.policy.service.GeneratedServicesModule;
import dev.vertique.examples.services.codegen.policy.service.PolicyHandlerService;
import dev.vertique.examples.services.codegen.policy.service.PolicyService;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.security.VertxAuthorizationImportModule;
import dev.vertique.rest.validation.RestValidationModule;
import dev.vertique.services.DispatchModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component of the typed access-policy proof application: JWT-authenticated REST
 * routes in front of two generated service contracts, with a recording authorization engine.
 *
 * <p>The registration modules are the ones the annotation processors generate for the test
 * compilation unit; nothing here replaces a generated contributor or client.
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            DispatchModule.class,
            CoreLifecycleStepsModule.class,
            JwtAuthModule.class,
            VertxAuthorizationImportModule.class,
            PolicyTestModule.class,
            GeneratedJaxRsResourcesModule.class,
            GeneratedServicesModule.class
        })
interface PolicyTestComponent extends VertiqueApplicationComponent {

    /**
     * Returns the generated client of the direct-implementation contract.
     *
     * @return the client
     */
    PolicyService policyService();

    /**
     * Returns the generated client of the handler contract.
     *
     * @return the client
     */
    PolicyHandlerService policyHandlerService();

    /**
     * Returns the business effect log.
     *
     * @return the log
     */
    Effects effects();

    /**
     * Returns the application-owned eligibility check.
     *
     * @return the check
     */
    Eligibility eligibility();

    /**
     * Returns the recording authorizer.
     *
     * @return the authorizer
     */
    RecordingAuthorizer authorizer();

    /**
     * Returns the authorization decision log.
     *
     * @return the log
     */
    DecisionLog decisions();

    /**
     * Returns the denial recovery interceptor.
     *
     * @return the interceptor
     */
    DenialRecovery denialRecovery();
}
