// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

/**
 * The size of the command queue at one instant, for the executor's queue snapshot.
 *
 * <p>INTERNAL: framework wiring between the executor and the command repository.
 *
 * @param available    the commands an executor could claim now
 * @param stuck        the commands reported stuck
 * @param parked       the parked commands
 * @param deadLettered the dead-lettered commands
 */
public record QueueCounts(long available, long stuck, long parked, long deadLettered) {}
