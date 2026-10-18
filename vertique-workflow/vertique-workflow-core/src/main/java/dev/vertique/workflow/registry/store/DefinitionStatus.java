// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

/** Durable definition lifecycle status. */
public enum DefinitionStatus {
    CANDIDATE,
    ACTIVATING,
    ACTIVE,
    SUPERSEDED,
    DEPRECATED,
    RETIRED
}
