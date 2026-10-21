// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

/**
 * Durable definition lifecycle status.
 *
 * <p>{@code PUBLISHING} and {@code AVAILABLE} are the publication lifecycle's statuses. {@code ACTIVATING},
 * {@code ACTIVE} and {@code SUPERSEDED} are the statuses they replace and are removed in the next breaking release;
 * nothing writes them any more.
 */
public enum DefinitionStatus {
    CANDIDATE,
    /** A node holds the fenced publication claim of the candidate. */
    PUBLISHING,
    /** The version is published and can be started, in any number of versions and scopes of one id. */
    AVAILABLE,
    ACTIVATING,
    ACTIVE,
    SUPERSEDED,
    DEPRECATED,
    RETIRED
}
