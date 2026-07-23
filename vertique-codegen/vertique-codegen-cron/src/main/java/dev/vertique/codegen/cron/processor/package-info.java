// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation processor for compile-time validation of {@code @CronJob}-annotated methods.
 *
 * <p>The single entry point is {@link dev.vertique.codegen.cron.processor.CronJobProcessor},
 * which orchestrates four validators:
 * <ul>
 *   <li>{@link dev.vertique.codegen.cron.processor.validate.CronExpressionValidator} — cron syntax</li>
 *   <li>{@link dev.vertique.codegen.cron.processor.validate.ServiceCouplingValidator} — service contract participation</li>
 *   <li>{@link dev.vertique.codegen.cron.processor.validate.PolicyValueValidator} — policy bounds</li>
 *   <li>{@link dev.vertique.codegen.cron.processor.validate.DuplicateIdValidator} — duplicate job ids</li>
 * </ul>
 *
 * <p>Pure validation — no code generation.
 */
package dev.vertique.codegen.cron.processor;
