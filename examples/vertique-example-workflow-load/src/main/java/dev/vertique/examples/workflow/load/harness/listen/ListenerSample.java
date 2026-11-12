// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import java.util.List;

/**
 * One reading of the listeners.
 *
 * @param timeMs the time of the reading, in epoch milliseconds
 * @param answered whether the database answered; an unanswered reading says nothing about the listeners
 * @param sessions the listeners the reading found
 */
public record ListenerSample(long timeMs, boolean answered, List<ListenerSession> sessions) {}
