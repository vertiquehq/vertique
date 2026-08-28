// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Observable bulkhead admission modes. */
public enum BulkheadMode {
    REJECT,
    QUEUE
}
