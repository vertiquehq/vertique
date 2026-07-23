// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dev.vertique.core.context.ContextValue;

/**
 * Minimal {@link ContextValue} wrapper for a tenant identifier, used across the engine module's
 * durable-context test suites (PRD-WF-007) so that the codec's type parameter satisfies the
 * {@code <T extends ContextValue>} bound on {@link dev.vertique.core.context.DurableContextMetadataEncoder}
 * / {@link dev.vertique.core.context.DurableContextMetadataDecoder}.
 *
 * <p>Modeled on {@code WorkflowTimerRecoveryDurableBindTest.StringCtx}
 * (vertique-workflow-delayed) — this is the reusable tenant-like test namespace shared across
 * the durable-context test suites in this module. This class is packaged into this module's
 * test-jar (see the {@code maven-jar-plugin} {@code test-jar} execution in this module's
 * {@code pom.xml}) so {@code vertique-workflow-postgresql} ITs reuse it directly via a
 * {@code test-jar}-typed test-scope dependency instead of declaring a colocated duplicate.
 *
 * @param tenantId the tenant identifier (e.g. {@code "T1"})
 */
public record TenantCtx(String tenantId) implements ContextValue {}
