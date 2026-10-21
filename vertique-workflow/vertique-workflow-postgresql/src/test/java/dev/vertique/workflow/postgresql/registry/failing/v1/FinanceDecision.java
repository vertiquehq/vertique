// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry.failing.v1;

/**
 * A decision payload that a document names by its binary name. The first deployment's type catalog lists it and a
 * later deployment's does not, although the class stays on the class path.
 *
 * @param by who decided
 */
public record FinanceDecision(String by) {}
