// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Qualifies the raw {@link dev.vertique.job.JobRepository} (backed by {@link PgJobRepository})
 * that the {@link NotifyingJobRepository} decorator wraps — avoids a self-referential binding
 * in {@link JobPostgresqlModule}.
 *
 * <p>Application code should never reference this qualifier. It is an implementation detail of
 * the two-step binding in {@link JobPostgresqlModule}: the raw PostgreSQL repository is bound
 * with {@code @RawJobRepository}, and the unqualified {@link dev.vertique.job.JobRepository}
 * resolves to the wrapping {@link NotifyingJobRepository}.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface RawJobRepository {}
