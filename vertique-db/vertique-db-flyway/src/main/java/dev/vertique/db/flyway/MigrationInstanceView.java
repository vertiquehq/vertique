// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Effective, secret-free configuration of one migration instance.
 *
 * <p>A view never carries a password or a placeholder value: it names the effective user and the
 * placeholder keys only. All collections are immutable.
 *
 * <p>{@code schema} is the first entry of the effective managed schema list, which is also the
 * instance's default schema, or {@code null} when no schema is configured. No connection is opened to
 * resolve it, so a {@code null} schema means the connection's own default schema applies.
 *
 * @param name the instance name
 * @param schema the first configured schema, or {@code null} when none is configured
 * @param schemas the effective managed schema list passed to Flyway
 * @param table the history table
 * @param locations the migration locations
 * @param mode the effective mode
 * @param target the effective target version, or {@code null} for the latest version
 * @param outOfOrder whether out-of-order migrations are allowed
 * @param baselineOnMigrate whether Flyway baselines a schema that has no history
 * @param baselineVersion the baseline version
 * @param validateOnMigrate whether Flyway validates before migrating
 * @param user the effective migration user; never a password
 * @param placeholderKeys the effective placeholder names, without values
 */
public record MigrationInstanceView(
        String name,
        String schema,
        List<String> schemas,
        String table,
        List<String> locations,
        FlywayMode mode,
        String target,
        boolean outOfOrder,
        boolean baselineOnMigrate,
        String baselineVersion,
        boolean validateOnMigrate,
        String user,
        Set<String> placeholderKeys) {

    /**
     * Creates a view with immutable copies of its collections.
     *
     * @throws NullPointerException if the name, table, mode, a collection or a
     *     collection element is {@code null}
     */
    public MigrationInstanceView {
        Objects.requireNonNull(name, "name must not be null");
        schemas = List.copyOf(Objects.requireNonNull(schemas, "schemas must not be null"));
        Objects.requireNonNull(table, "table must not be null");
        locations = List.copyOf(Objects.requireNonNull(locations, "locations must not be null"));
        Objects.requireNonNull(mode, "mode must not be null");
        placeholderKeys = Set.copyOf(Objects.requireNonNull(placeholderKeys, "placeholderKeys must not be null"));
    }
}
