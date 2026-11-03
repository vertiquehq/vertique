// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.workflow;

import dev.vertique.workflow.types.WorkflowType;

/**
 * Durable state of a load workflow instance.
 *
 * @param key the key the instance was started with
 */
@WorkflowType("load")
public record LoadState(String key) {}
