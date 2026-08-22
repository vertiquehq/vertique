// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.input.processing.InputObjectProcessor;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;

/**
 * Fails composition when no {@link InputObjectProcessor} binding exists (T014, contract §4.7).
 *
 * <p>Every {@code McpServerModule} composition requires a direct, non-{@code Optional}
 * {@link InputObjectProcessor} binding — Dagger compilation is the startup gate, even when the
 * currently registered tool set declares no policies. This validator never calls
 * {@link InputObjectProcessor#declaresPolicies(java.lang.reflect.Type)} to weaken that requirement:
 * merely requiring the binding through this <em>constructible-as-validation</em> constructor (see
 * {@link ComposeValidator}) is the whole proof. A composition that omits a module providing
 * {@link InputObjectProcessor} fails Dagger code generation before any route mounts, naming the
 * missing type.
 *
 * <p>This validator performs no INP traversal, policy resolution, or request-time processing of its
 * own — {@link InputObjectProcessor#processInput} is never called here. The request-time pipeline
 * that calls it is out of this class's scope, and MCP defines no ADR-0214-rejected shortcut API of
 * its own to reach it early.
 */
@Singleton
final class McpInputProcessingCompositionValidator implements ComposeValidator {

    /**
     * Requires the mandatory {@link InputObjectProcessor} binding; constructing this validator is
     * the entire proof, matching {@link McpJsonProfileDefaultValidator}'s established pattern.
     *
     * @param inputObjectProcessor the mandatory engine binding; a missing binding fails Dagger
     *     compilation before this constructor could ever run
     */
    @Inject
    McpInputProcessingCompositionValidator(InputObjectProcessor inputObjectProcessor) {
        Objects.requireNonNull(inputObjectProcessor, "inputObjectProcessor");
    }
}
