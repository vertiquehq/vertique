// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.db.DbModule;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.examples.webhook.webhook.WebhookModule;
import dev.vertique.job.delayed.dagger.DelayedJobModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.rest.client.RestClientModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.validation.RestValidationModule;
import dev.vertique.security.runtime.IdentitySnapshotReconstructionModule;
import dev.vertique.services.DispatchModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component for the example-webhook application.
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
 * verticle deploys (so the job tables exist before any poller starts — replacing the manual
 * {@code migrationRunner().migrate(vertx)} call); the {@code INFRA}-phase verticle (management)
 * deploys; the {@code SERVICES}-phase {@code ServiceDeploymentStartupStep} contributed by
 * {@link DispatchModule} runs {@code serviceDeploymentManager().deployAll()} before any
 * {@code SERVICES}-phase verticle (the framework background workers — delayed-job pollers, cron
 * lifecycle); then the {@code EDGE}-phase verticle (HTTP) deploys. This preserves the legacy
 * migrate &rarr; {@code INFRA} &rarr; service-dispatch-deploy &rarr; {@code SERVICES} &rarr;
 * {@code EDGE} ordering.
 *
 * <p>Includes:
 * <ul>
 *   <li>{@link VertxModule} — Vert.x instance and configuration</li>
 *   <li>{@link RestModule} — JAX-RS annotation-driven routing</li>
 *   <li>{@link RestValidationModule} — default {@code web-validation} request-validation strategy</li>
 *   <li>{@link RestClientModule} — declarative HTTP client infrastructure</li>
 *   <li>{@link DispatchModule} — event bus service dispatch infrastructure; also contributes the
 *       paired {@code SERVICES}-phase service deploy/undeploy lifecycle steps</li>
 *   <li>{@link DbModule} — database pool configuration</li>
 *   <li>{@link DbPostgresqlModule} — PostgreSQL pool and failure mapper</li>
 *   <li>{@link DbFlywayModule} — Flyway migration runner and its {@code MIGRATE}-phase startup step</li>
 *   <li>{@link DelayedJobModule} — delayed job queue (includes JobModule, JobPostgresqlModule,
 *       JobCoordinatorModule)</li>
 *   <li>{@link IdentitySnapshotReconstructionModule} — identity-snapshot durable carriage
 *       (config-backed HMAC keyset, codec, encoder/decoder, capture seam) plus the receive-side
 *       reconstruction initializer, so a delayed job scheduled on behalf of an authenticated user
 *       runs with that user reconstructed as its subject-of-record. Requires the
 *       {@code identity.snapshot.hmacKeys.active} config section (see the example config)</li>
 *   <li>{@link ManagementModule} — health check endpoints on management port</li>
 *   <li>{@link CoreLifecycleStepsModule} — framework {@code CONFIGURE}/{@code VALIDATE} lifecycle
 *       steps (Jackson configuration + compose-validator harness)</li>
 *   <li>{@link AppModule} — application-specific configuration and security stub bindings</li>
 *   <li>{@link WebhookModule} — webhook REST client, executor, and client proxy</li>
 * </ul>
 *
 * <p>Security modules ({@code AuthModule}, {@code SecurityModule}) are intentionally excluded
 * — this example focuses on the typed delayed job contract pattern.
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            RestClientModule.class,
            DispatchModule.class,
            DbModule.class,
            DbPostgresqlModule.class,
            DbFlywayModule.class,
            DelayedJobModule.class,
            IdentitySnapshotReconstructionModule.class,
            ManagementModule.class,
            CoreLifecycleStepsModule.class,
            AppModule.class,
            WebhookModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
