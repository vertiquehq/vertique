// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

/** How far the actor recorded on a history event can be relied on. */
public enum HistoryAttribution {
    /** The actor is the verified identity of the caller. */
    VERIFIED,
    /** There was no identity to attribute to. */
    ABSENT,
    /** The identity was rebuilt from a snapshot that could not be verified, and the work continued. */
    DEGRADED
}
