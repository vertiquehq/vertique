// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

/**
 * Validation group for audit-only constraints.
 *
 * <p>Deliberately does <em>not</em> extend {@code jakarta.validation.groups.Default}: a method that
 * requests only this group (or a subgroup) must not activate {@code Default} constraints, and a
 * method that requests {@code Default} must not activate this group's constraints.
 */
public interface Audit {}
