// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Shared integration-test support for the {@code vertique-kafka} family: a reachability-verified,
 * shared Testcontainers Kafka broker ({@link dev.vertique.kafka.test.KafkaTestContainers})
 * reused across all Kafka IT classes in a Surefire/Failsafe fork. Intended for test scope in
 * framework modules and example applications.
 */
package dev.vertique.kafka.test;
