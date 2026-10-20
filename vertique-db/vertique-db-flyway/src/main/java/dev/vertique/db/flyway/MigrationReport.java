// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.util.List;
import java.util.Objects;

/**
 * Ordered outcome of one migration run: one entry per instance, in execution order. Entries after a
 * failure are {@link MigrationStatus#NOT_RUN}; earlier successful entries stay committed, so the
 * report never implies that the batch was atomic.
 *
 * @param instances the entries in execution order; an immutable copy
 */
public record MigrationReport(List<MigrationInstanceResult> instances) {

    /**
     * Creates a report from an immutable copy of the entries.
     *
     * @throws NullPointerException if the list or any entry is {@code null}
     */
    public MigrationReport {
        instances = List.copyOf(Objects.requireNonNull(instances, "instances must not be null"));
    }
}
