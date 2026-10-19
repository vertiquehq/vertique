// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Effective, secret-free configuration of one migration instance.
 *
 * <p>A view never carries a password or a placeholder value: it names the effective user, where the
 * credential pair came from, and the placeholder keys only. All collections are immutable.
 *
 * <p>In a view resolved without a connection, {@code schema} is the instance's default and history
 * schema when it is known (its {@code db.schemas} entry, otherwise the first entry of its managed
 * list) and {@code null} when the connection's default schema applies. In a run report, an attempted
 * entry carries the history schema actually used.
 *
 * @param name the instance name
 * @param schema the default and history schema, or {@code null} for the connection default
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
 * @param credentialSource the level the credential pair came from
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
        CredentialSource credentialSource,
        Set<String> placeholderKeys) {

    /**
     * Creates a view with immutable copies of its collections.
     *
     * @throws NullPointerException if the name, table, mode, credential source, a collection or a
     *     collection element is {@code null}
     */
    public MigrationInstanceView {
        Objects.requireNonNull(name, "name must not be null");
        schemas = List.copyOf(Objects.requireNonNull(schemas, "schemas must not be null"));
        Objects.requireNonNull(table, "table must not be null");
        locations = List.copyOf(Objects.requireNonNull(locations, "locations must not be null"));
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(credentialSource, "credentialSource must not be null");
        placeholderKeys = Set.copyOf(Objects.requireNonNull(placeholderKeys, "placeholderKeys must not be null"));
    }

    /**
     * Returns a copy of this view that reports the given history schema.
     *
     * @param actualSchema the history schema actually used
     * @return the updated view
     */
    MigrationInstanceView withSchema(String actualSchema) {
        return new MigrationInstanceView(
                name,
                actualSchema,
                schemas,
                table,
                locations,
                mode,
                target,
                outOfOrder,
                baselineOnMigrate,
                baselineVersion,
                validateOnMigrate,
                user,
                credentialSource,
                placeholderKeys);
    }
}
