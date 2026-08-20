// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing.internal;

/**
 * Synthetic test-scope stand-in for an implementation (non-API) type of the input-processing artifact. The artifact
 * publishes exactly one API package, {@code dev.vertique.input.processing}; anything below it is implementation and is
 * off limits to MCP.
 *
 * <p>It exists only so {@code McpPrerequisiteArchitectureTest} has a real bytecode dependency target for its
 * implementation-package rule. Nothing in production may depend on it.
 */
public final class SyntheticPrerequisiteImplementationType {
    private SyntheticPrerequisiteImplementationType() {}
}
