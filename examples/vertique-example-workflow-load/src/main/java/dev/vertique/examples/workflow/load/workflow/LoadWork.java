// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.workflow;

/**
 * The command the workflow dispatches to the load service.
 *
 * @param key the key of the instance that dispatched it
 */
public record LoadWork(String key) {}
