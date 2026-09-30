// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

/**
 * Validation group extending {@link Audit} only, so requesting it activates {@link Audit}
 * constraints (by {@code isAssignableFrom}) but never {@code Default} ones.
 */
public interface StrictAudit extends Audit {}
