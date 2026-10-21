// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

/**
 * Reserved scope values.
 *
 * <p>The default scope is a value, not a stored record: it always exists, and rows written without a scope belong
 * to it. It replaces the absent scope of earlier releases.
 */
public final class WorkflowScopes {

    /** The default scope, {@code "default"}: the scope of every row that names no other. */
    public static final String DEFAULT = "default";

    private WorkflowScopes() {}
}
