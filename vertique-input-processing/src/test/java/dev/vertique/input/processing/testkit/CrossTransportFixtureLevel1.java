// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing.testkit;

import dev.vertique.core.sanitization.Sanitize;
import java.util.List;
import java.util.Map;

/**
 * The root (depth-1) record of {@link CrossTransportInputCorpus}'s published fixture graph: the same
 * scalar/list/map leaf shape as {@link CrossTransportFixtureLevel2}, plus one nested {@code Level2} —
 * so the corpus exercises record, collection, map, and nested-record traversal in a single shared
 * shape both REST and MCP materialize.
 *
 * @param scalar  a bare string leaf
 * @param items   a {@code List<String>} leaf
 * @param entries a {@code Map<String, String>} leaf
 * @param nested  the nested depth-2 record
 */
public record CrossTransportFixtureLevel1(
        @Sanitize(CrossTransportUppercaseSanitizer.class) String scalar,
        @Sanitize(CrossTransportUppercaseSanitizer.class) List<String> items,
        @Sanitize(CrossTransportUppercaseSanitizer.class) Map<String, String> entries,
        CrossTransportFixtureLevel2 nested) {}
