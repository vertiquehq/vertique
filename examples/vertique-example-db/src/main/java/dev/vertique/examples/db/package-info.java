// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Example application demonstrating a CRUD REST API backed by PostgreSQL with Flyway schema
 * migrations. There is no hand-written {@code MainVerticle}: the entry point is
 * {@link dev.vertique.examples.db.AppComponent}, the root Dagger {@code @Component} annotated
 * {@link dev.vertique.application.VertiqueApp} and extending
 * {@link dev.vertique.application.VertiqueApplicationComponent}. The
 * {@code vertique-codegen-application} annotation processor generates
 * {@code AppComponentVertiqueComponentFactory} (and its {@code META-INF/services} registration) for
 * that component; at runtime the launcher's bootstrap verticle discovers that factory and the
 * host-neutral lifecycle runner ({@code VertiqueApplicationBootstrap}) builds the component from the
 * pre-resolved {@code config()} tree and drives startup per lifecycle phase. The runner reproduces
 * the legacy choreography: Jackson configuration via {@code CoreLifecycleStepsModule}
 * ({@code CONFIGURE}); the {@code MIGRATE}-phase {@code FlywayMigrationStartupStep} contributed by
 * {@code DbFlywayModule} runs the Flyway migration before any verticle deploys (replacing the manual
 * {@code migrationRunner().migrate(vertx)} call); then the management verticle
 * ({@link dev.vertique.management.ManagementVerticle} in {@code INFRA}) and the
 * {@link dev.vertique.rest.core.router.HttpVerticle} ({@code EDGE}) — preserving the legacy
 * migrate &rarr; deploy ordering.
 *
 * <p>{@link dev.vertique.examples.db.AppComponent} wires together {@code VertxModule},
 * {@code RestModule}, {@code DbModule}, {@code DbPostgresqlModule}, {@code DbFlywayModule},
 * {@code AppModule}, and {@code ResourceModule}. {@code AppModule} declares the verticle deployments
 * and the null security stand-ins; {@code ResourceModule} registers the {@code ItemResource} JAX-RS
 * endpoint and its exception mappers. This module serves as a reference implementation for building
 * Vert.x REST APIs backed by a relational database with managed schema migrations.
 */
package dev.vertique.examples.db;
