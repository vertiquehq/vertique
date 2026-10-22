// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

/**
 * Durable definition lifecycle status.
 *
 * <p>A candidate is published through a fenced claim: {@code CANDIDATE} to {@code PUBLISHING} to {@code AVAILABLE}.
 * Any number of versions and scopes of one definition id are {@code AVAILABLE} at once.
 */
public enum DefinitionStatus {
    CANDIDATE,
    /** A node holds the fenced publication claim of the candidate. */
    PUBLISHING,
    /** The version is published and can be started, in any number of versions and scopes of one id. */
    AVAILABLE,
    DEPRECATED,
    RETIRED
}
