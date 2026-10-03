// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.body;

import java.util.HashMap;

/**
 * A map whose value type is itself. The generator describes it as a root {@code $defs} entry named
 * {@code RecursiveMap} whose {@code additionalProperties} references that same entry.
 */
public class RecursiveMap extends HashMap<String, RecursiveMap> {}
