// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.util.List;
import java.util.Map;

/**
 * Validated named migration batch: the views in execution order plus the secret values needed to
 * run them. Its rendering never includes a password or a placeholder value.
 *
 * @param instances the resolved instances in execution order
 * @param jdbcUrl the root JDBC destination, or {@code null} when the root mode is disabled
 * @param support the selected database support, or {@code null} when the root mode is disabled
 * @param sessionUser the user that opens the database-support session
 * @param sessionPassword the password that opens the database-support session
 * @param lockTimeoutMs the upper bound on waiting for the coordination lock, or {@code 0} when the root
 *     mode is disabled
 * @param rootFirstSchema the first entry of the root schema list, or {@code null} when it has none or
 *     the root mode is disabled
 */
record NamedPlan(
        List<Instance> instances,
        String jdbcUrl,
        DatabaseSupport support,
        String sessionUser,
        String sessionPassword,
        long lockTimeoutMs,
        String rootFirstSchema) {

    NamedPlan {
        instances = List.copyOf(instances);
    }

    /**
     * Returns the views in execution order.
     *
     * @return the immutable views
     */
    List<MigrationInstanceView> views() {
        return instances.stream().map(Instance::view).toList();
    }

    @Override
    public String toString() {
        return "NamedPlan[instances=" + instances + "]";
    }

    /**
     * One resolved instance.
     *
     * @param view the secret-free view
     * @param password the selected migration password
     * @param placeholders the effective placeholder values
     */
    record Instance(MigrationInstanceView view, String password, Map<String, String> placeholders) {

        Instance {
            placeholders = Map.copyOf(placeholders);
        }

        @Override
        public String toString() {
            return "Instance[view=" + view + "]";
        }
    }
}
