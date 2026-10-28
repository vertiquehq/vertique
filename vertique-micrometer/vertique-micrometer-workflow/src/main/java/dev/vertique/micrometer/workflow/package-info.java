// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Micrometer workflow metrics adapter.
 *
 * <p>This package bridges workflow executor events into Micrometer meters. It is observe-only: it
 * records command, retry, lease and queue metrics but never modifies workflow execution.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.micrometer.workflow.MicrometerWorkflowModule} — Dagger module contributing
 *       the workflow metrics observer via multibinding; install alongside
 *       {@link dev.vertique.micrometer.MicrometerModule} and the application's workflow modules</li>
 *   <li>{@link dev.vertique.micrometer.workflow.WorkflowExecutorMetricsObserver} — records bounded
 *       counters, a timer and queue gauges in the injected
 *       {@link io.micrometer.core.instrument.MeterRegistry}</li>
 * </ul>
 */
package dev.vertique.micrometer.workflow;
