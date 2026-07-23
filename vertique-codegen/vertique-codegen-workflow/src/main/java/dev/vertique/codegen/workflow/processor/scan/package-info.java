// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * APT-side scanning for {@code @WorkflowContract} interfaces: classifies each method as a
 * {@code @WorkflowStart}, {@code @WorkflowSignal}, or {@code @WorkflowQuery} operation and resolves
 * parameter roles (payload, instance id, idempotency/business/subject/dedup keys).
 */
package dev.vertique.codegen.workflow.processor.scan;
