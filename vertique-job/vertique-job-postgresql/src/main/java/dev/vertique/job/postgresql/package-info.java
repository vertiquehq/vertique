// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * PostgreSQL implementation of the job persistence SPI.
 *
 * <p>Provides {@link dev.vertique.job.postgresql.PgJobRepository} backed by the Vert.x reactive
 * PostgreSQL client, with Flyway migrations for the job schema.
 *
 * <p>Include {@link dev.vertique.job.postgresql.JobPostgresqlModule} in your Dagger component to
 * bind {@link dev.vertique.job.JobRepository} to this implementation.
 */
package dev.vertique.job.postgresql;
