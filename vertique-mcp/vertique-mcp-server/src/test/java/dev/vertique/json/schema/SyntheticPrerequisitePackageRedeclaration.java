// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

/**
 * Synthetic test-scope fixture for {@code McpPrerequisiteArchitectureTest}: it deliberately redeclares the
 * {@code dev.vertique.json.schema} package owned by the JSON-schema artifact, so the ownership rule has exactly one
 * class to catch. Moving it to a neutral package must drop that rule's violation count from 1 to 0.
 *
 * <p>Nothing in production may follow this example.
 */
public final class SyntheticPrerequisitePackageRedeclaration {
    private SyntheticPrerequisitePackageRedeclaration() {}
}
