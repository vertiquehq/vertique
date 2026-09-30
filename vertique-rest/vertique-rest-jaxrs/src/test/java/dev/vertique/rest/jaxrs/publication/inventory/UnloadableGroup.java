// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

/**
 * Validation group that {@link UnreadableGroupsResource} names on a constraint and that the proof
 * makes unloadable from that resource's class loader, as when a group class is missing from the
 * runtime class path. Reading such a constraint's {@code groups()} then throws {@link
 * TypeNotPresentException}.
 */
public interface UnloadableGroup {}
