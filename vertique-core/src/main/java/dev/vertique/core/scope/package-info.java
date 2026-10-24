// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Shared scope vocabulary for naming a data or execution partition.
 *
 * <p>This package declares immutable value types only. Constructing a key or context installs no
 * runtime binding, codec, adapter, or authority. Applications supply their own meaning for a
 * tenant, project, workspace, or organization; authenticated resolution and propagation belong
 * elsewhere.
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@link dev.vertique.core.scope.ScopeKey} — validated exact-identity partition name, with
 *       an ordinary {@link dev.vertique.core.scope.ScopeKey#DEFAULT DEFAULT} constant.
 * </ul>
 */
package dev.vertique.core.scope;
