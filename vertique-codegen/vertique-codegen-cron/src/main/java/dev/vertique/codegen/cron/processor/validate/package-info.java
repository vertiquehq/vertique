// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Validator classes used by {@link dev.vertique.codegen.cron.processor.CronJobProcessor} to
 * validate individual aspects of {@code @CronJob}-annotated methods at compile time.
 *
 * <p>Each validator is focused on a single concern and emits {@link javax.tools.Diagnostic.Kind#ERROR}
 * or {@link javax.tools.Diagnostic.Kind#WARNING} diagnostics via the shared
 * {@link dev.vertique.codegen.CodegenContext}.
 */
package dev.vertique.codegen.cron.processor.validate;
