// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Micrometer outbox metrics adapter.
 *
 * <p>This package bridges the transactional outbox into Micrometer meters. It is observe-only: it
 * counts publish attempts and reads the queue size but never changes what the relay does.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.micrometer.outbox.MicrometerOutboxModule} — Dagger module contributing the
 *       publish observer and the queue sampler; install alongside
 *       {@link dev.vertique.micrometer.MicrometerModule} and the application's outbox modules</li>
 *   <li>{@link dev.vertique.micrometer.outbox.OutboxMetricsConfig} — the {@code metrics.outbox} settings</li>
 * </ul>
 */
package dev.vertique.micrometer.outbox;
