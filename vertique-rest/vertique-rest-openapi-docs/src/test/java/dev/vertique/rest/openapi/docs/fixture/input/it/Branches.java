// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import java.io.Serial;
import java.util.HashMap;

/**
 * A self-referential map: every value is again a {@code Branches}. The input-direction generator
 * describes it as a root {@code $defs} entry whose {@code additionalProperties} references itself,
 * so a body holding it carries local definitions that publication relocates.
 */
public class Branches extends HashMap<String, Branches> {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates an empty map. */
    public Branches() {}
}
