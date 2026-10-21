// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import java.util.List;

/**
 * A list of workflow contracts and workflow types that documents loaded at runtime may name.
 *
 * <p>The workflow annotation processor generates one catalog module for each compilation unit that
 * declares {@code @WorkflowContract} interfaces or {@link WorkflowType} classes; the module
 * contributes one catalog to the set the workflow core module collects. An application can also
 * contribute a hand-written catalog with a {@code @Provides @IntoSet} method, for a class the
 * generated module cannot reference.
 *
 * <p>At startup the contributed catalogs are merged into the one type-name index. A document that
 * is loaded through the definition service or restored from the store resolves its contract, state
 * and payload names through that index only, so a catalog is the allow-list of the classes such a
 * document can reach.
 */
@FunctionalInterface
public interface WorkflowTypeCatalog {

    /**
     * Returns the entries of this catalog.
     *
     * @return the entries; never {@code null}
     */
    List<WorkflowTypeCatalogEntry> entries();
}
