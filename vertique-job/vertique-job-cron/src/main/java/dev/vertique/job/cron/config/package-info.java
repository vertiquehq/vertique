// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Typed configuration records for the {@code cron} section, parsed at the {@code CronModule} /
 * {@code CronPersistenceModule} boundary via {@link dev.vertique.core.config.ConfigParser}.
 *
 * <p>{@link dev.vertique.job.cron.config.CronConfig} models the {@code cron} section; its
 * {@code jobs} keyed object ({@code cron.jobs.{id}}) is injected into
 * {@link dev.vertique.job.cron.config.CronJobConfig#id()} via
 * {@link dev.vertique.core.json.KeyedBy @KeyedBy("id")}. Module internals depend on these typed
 * records, never on the raw {@code @VertxConfig JsonObject}; the complex cron resolution rules stay
 * in {@code CronJobRegistrar}.
 */
package dev.vertique.job.cron.config;
