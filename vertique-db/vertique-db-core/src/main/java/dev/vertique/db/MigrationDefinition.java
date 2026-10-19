// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, explicit ownership of migration resources by one named migration instance. A
 * definition carries no runtime services.
 *
 * <p>Construction only rejects {@code null} and takes defensive immutable copies of the lists. The
 * name and location syntax is validated by the migration runner that consumes the definition, not
 * here.
 *
 * @param name the instance name
 * @param locations the scheme-qualified resource locations the instance migrates from (for example
 *     {@code classpath:db/migration/job})
 * @param dependsOn the names of the instances that must be migrated before this one
 */
public record MigrationDefinition(String name, List<String> locations, List<String> dependsOn) {

    /**
     * Creates a definition.
     *
     * @throws NullPointerException if the name, either list or any list element is {@code null}
     */
    public MigrationDefinition {
        Objects.requireNonNull(name, "name must not be null");
        locations = List.copyOf(Objects.requireNonNull(locations, "locations must not be null"));
        dependsOn = List.copyOf(Objects.requireNonNull(dependsOn, "dependsOn must not be null"));
    }
}
