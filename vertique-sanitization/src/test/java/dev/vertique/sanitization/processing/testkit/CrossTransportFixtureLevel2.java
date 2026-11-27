// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.processing.testkit;

import dev.vertique.sanitization.Sanitize;
import java.util.List;
import java.util.Map;

/**
 * The nested (depth-2) leaf record of {@link CrossTransportInputCorpus}'s fixture graph: one scalar
 * string, one string list, and one string map, each declaring {@link CrossTransportUppercaseSanitizer}
 * so the input processor discovers and runs it identically regardless of which transport materializes this type —
 * field-level policy resolution is transport-neutral.
 *
 * @param scalar  a bare string leaf
 * @param items   a {@code List<String>} leaf
 * @param entries a {@code Map<String, String>} leaf
 */
public record CrossTransportFixtureLevel2(
        @Sanitize(CrossTransportUppercaseSanitizer.class) String scalar,
        @Sanitize(CrossTransportUppercaseSanitizer.class) List<String> items,
        @Sanitize(CrossTransportUppercaseSanitizer.class) Map<String, String> entries) {}
