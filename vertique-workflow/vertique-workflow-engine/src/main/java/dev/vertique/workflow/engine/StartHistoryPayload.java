// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Typed payload for a {@code START} history entry.
 *
 * <p>Records the definition id, version, and optional business key captured when a workflow
 * instance is first created.
 *
 * @param definitionId the workflow definition id
 * @param version the pinned definition version stored on the instance
 * @param businessKey optional caller-supplied business key; {@code null} when not provided
 */
record StartHistoryPayload(String definitionId, long version, String businessKey) {}
