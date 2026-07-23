// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.db.DbModule;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.db.resource.ResourceModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.validation.RestValidationModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component for the example-db application.
 *
 * <p>The component is annotated {@link VertiqueApp} and {@code extends}
 * {@link VertiqueApplicationComponent}, so the framework's {@code vertique-codegen-application}
 * annotation processor generates {@code AppComponentVertiqueComponentFactory} (plus its
 * {@code META-INF/services} registration) and the host-neutral lifecycle runner
 * ({@code VertiqueApplicationBootstrap}) drives startup and shutdown — there is no hand-written
 * {@code MainVerticle}. The inherited {@code startupSteps()}/{@code shutdownSteps()}/
 * {@code verticleDeploymentManager()} accessors expose the lifecycle inputs the runner consumes.
 *
 * <p>The runner reproduces the old {@code MainVerticle} choreography exactly through lifecycle
 * phases: Jackson configuration runs as the {@code CONFIGURE}-phase step contributed by
 * {@link CoreLifecycleStepsModule}; the {@code MIGRATE}-phase {@code FlywayMigrationStartupStep}
 * contributed by {@link DbFlywayModule} runs {@code migrationRunner().migrate(vertx)} before any
 * verticle deploys; then the verticles deploy ({@code INFRA} management first, then {@code EDGE}
 * HTTP). This preserves the legacy migrate &rarr; deploy ordering — and because Flyway is now a
 * runner step, the application must no longer call {@code migrationRunner().migrate(vertx)} by hand.
 *
 * <p>Includes:
 * <ul>
 *   <li>{@link VertxModule} — Vert.x instance and configuration</li>
 *   <li>{@link RestModule} — JAX-RS annotation-driven routing</li>
 *   <li>{@link RestValidationModule} — default {@code web-validation} request-validation strategy</li>
 *   <li>{@link DbModule} — database pool configuration</li>
 *   <li>{@link DbPostgresqlModule} — PostgreSQL pool and failure mapper</li>
 *   <li>{@link DbFlywayModule} — Flyway migration runner and its {@code MIGRATE}-phase startup step</li>
 *   <li>{@link ManagementModule} — Health check endpoints on management port</li>
 *   <li>{@link DeployerModule} — Verticle deployment multibinding</li>
 *   <li>{@link CoreLifecycleStepsModule} — framework {@code CONFIGURE}/{@code VALIDATE} lifecycle
 *       steps (Jackson configuration + compose-validator harness)</li>
 *   <li>{@link AppModule} — application-specific configuration bindings and verticle deployments</li>
 *   <li>{@link ResourceModule} — JAX-RS resource and exception mapper registration</li>
 * </ul>
 *
 * <p>Security modules ({@code AuthModule}, {@code SecurityModule}) are intentionally excluded —
 * this example focuses on database operations, not authentication.
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            DbModule.class,
            DbPostgresqlModule.class,
            DbFlywayModule.class,
            ManagementModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            AppModule.class,
            ResourceModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
