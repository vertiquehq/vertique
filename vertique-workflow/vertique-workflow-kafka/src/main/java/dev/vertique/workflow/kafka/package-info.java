// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/** Kafka signal sources for workflows: {@code WorkflowKafkaSignals} starts a {@code KafkaSignals} builder that turns the records of a topic into correlated workflow signals, and a {@code KafkaSignalSource} class does the same with its dependencies injected and no {@code @Provides} method. */
package dev.vertique.workflow.kafka;
