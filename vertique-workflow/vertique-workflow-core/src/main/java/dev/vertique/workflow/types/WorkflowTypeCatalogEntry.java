// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import java.util.Objects;

/**
 * One class listed in a {@link WorkflowTypeCatalog} under its logical name.
 *
 * <p>Contracts and types are separate namespaces: a contract and a type may share a name. Within one
 * namespace a name maps to one class; two catalogs that list different classes under the same name
 * fail startup with {@code WorkflowTypeNameConflictException}, while listing the same class twice
 * under the same name is not a conflict.
 *
 * @param kind the namespace the name belongs to; must not be {@code null}
 * @param name the logical name, or the binary class name for a class without a logical name; must
 *     not be {@code null}
 * @param type the class; must not be {@code null}
 */
public record WorkflowTypeCatalogEntry(Kind kind, String name, Class<?> type) {

    /** Validates the components. */
    public WorkflowTypeCatalogEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    /** The namespace of a catalog entry. */
    public enum Kind {
        /** A {@code @WorkflowContract} interface. */
        CONTRACT,
        /** A state or payload class, named by {@link WorkflowType}. */
        TYPE
    }
}
