// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * A flat output entity with the scalar members {@code title} and {@code status}, declared as the
 * content of a non-success response.
 */
public class Problem {

    /** A short summary. */
    public String title;

    /** The HTTP status code. */
    public int status;

    /** Creates an empty problem. */
    public Problem() {}
}
