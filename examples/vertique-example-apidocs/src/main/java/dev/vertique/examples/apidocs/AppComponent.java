// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.apidocs.resource.GeneratedJaxRsResourcesModule;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.OpenApiDocsModule;
import dev.vertique.rest.validation.RestValidationModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component of the example-apidocs application.
 *
 * <p>The component is annotated {@link VertiqueApp} and {@code extends}
 * {@link VertiqueApplicationComponent}, so the framework's annotation processor generates
 * {@code AppComponentVertiqueComponentFactory} and the host-neutral lifecycle runner drives startup
 * and shutdown.
 *
 * <p>Includes:
 * <ul>
 *   <li>{@link VertxModule} — Vert.x instance and configuration</li>
 *   <li>{@link ConfigParsingModule} — typed configuration parsing</li>
 *   <li>{@link RestModule} — JAX-RS annotation-driven routing of the declared applications</li>
 *   <li>{@link RestValidationModule} — default {@code web-validation} request-validation strategy</li>
 *   <li>{@link JwtAuthModule} — JWT bearer authentication under the {@code bearerAuth} scheme and
 *       role authorization from the token's {@code roles} claim</li>
 *   <li>{@link OpenApiDocsModule} — publishes the OpenAPI document of every declared application
 *       annotated {@code @ApiDocs} under {@code /apidocs}</li>
 *   <li>{@link DeployerModule} — verticle deployment multibinding</li>
 *   <li>{@link CoreLifecycleStepsModule} — framework {@code CONFIGURE}/{@code VALIDATE} lifecycle
 *       steps</li>
 *   <li>{@link AppModule} — the {@code JWTAuth} binding, the HTTP verticle deployment, and the API
 *       documentation page</li>
 *   <li>{@link GeneratedJaxRsResourcesModule} — generated at compile time: the resource bindings and
 *       the registrations of {@link PublicApi} and {@link ManagementApi}</li>
 * </ul>
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            JwtAuthModule.class,
            OpenApiDocsModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            AppModule.class,
            GeneratedJaxRsResourcesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
