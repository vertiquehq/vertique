// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.sideeffect;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Dagger {@link Qualifier} that disambiguates the {@code Set<WorkflowSideEffectRecorder<?>>}
 * multibinding from any other similarly-typed sets in the application graph.
 *
 * <p>This annotation is placed on {@code @Multibinds} declarations in {@code WorkflowPostgresqlModule}
 * and on each {@code @IntoSet} provider method in modules that contribute recorders (e.g.,
 * {@code WorkflowServicesModule}).
 *
 * <p>Using an explicit qualifier is preferable to relying on type alone because the raw-typed
 * {@code Set<WorkflowSideEffectRecorder<SqlClient>>} can ambiguously match other generic set
 * bindings in large component graphs.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkflowRecorders {}
