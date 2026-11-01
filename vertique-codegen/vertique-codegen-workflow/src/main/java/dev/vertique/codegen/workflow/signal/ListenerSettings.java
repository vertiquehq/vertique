// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.signal;

/**
 * What {@code @KafkaSignalListener} states on an interface: the consumer's name, the topic it reads and its consumer
 * group.
 *
 * @param name the name of the consumer, which names the signal source and its observer events
 * @param topic the topic the source reads
 * @param groupId the consumer group of the source
 */
record ListenerSettings(String name, String topic, String groupId) {}
