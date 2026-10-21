// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/** Workflow client factory: {@code WorkflowClientFactory} creates type-safe clients from a definition class or a contract and a definition id, each bound by a {@code WorkflowClientBinding} — preferring the generated zero-reflection {@code {Contract}_WorkflowClientProxy} when present, else a reflective JDK dynamic proxy; {@code WorkflowProxyValidator} enforces the contract's method rules at creation time. */
package dev.vertique.workflow.client;
