// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.dagger;

/**
 * Empty marker type bound exclusively by {@link CronPersistenceModule}.
 *
 * <p>Exists so consumers can prove at Dagger compile time that the persistence-backed cron flavor
 * is installed (not the in-memory {@link CronModule}). Use case: a compose-time validator that
 * requires {@code SINGLE_INSTANCE} cron support can declare
 * {@code CronPersistenceMarker} as a constructor dependency; Dagger will fail to compile if only
 * {@link CronModule} is on the graph because {@code CronModule} does not provide this binding.
 *
 * <p>{@link CronJobRegistrar} / {@link CronScheduler} bindings on their own are not sufficient
 * to prove the persistence flavor because both cron modules provide them — {@link CronModule}
 * just passes a {@code null} {@link dev.vertique.job.JobRepository} to the constructor.
 * {@link dev.vertique.job.JobRepository} on its own is also not sufficient because it is typically
 * bound by an unrelated module (e.g. {@code JobPostgresqlModule}) and can coexist with
 * {@link CronModule}.
 *
 * <h2>No {@code @Inject} constructor</h2>
 *
 * <p>This class deliberately has no {@code @Inject}-annotated constructor. Dagger's implicit
 * just-in-time binding would otherwise satisfy {@code CronPersistenceMarker} in any component,
 * including one that only installs {@link CronModule} — defeating the purpose of the marker.
 * The only path to a {@code CronPersistenceMarker} instance is the explicit
 * {@code @Provides} method on {@link CronPersistenceModule}.
 *
 * <p>The class has no behavior. Its only purpose is to be a uniquely-named Dagger binding key.
 */
public final class CronPersistenceMarker {

    /**
     * Package-private constructor. Called only by {@link CronPersistenceModule}'s explicit
     * {@code @Provides} method; no {@code @Inject} so Dagger cannot satisfy this binding via
     * just-in-time injection.
     */
    CronPersistenceMarker() {}
}
