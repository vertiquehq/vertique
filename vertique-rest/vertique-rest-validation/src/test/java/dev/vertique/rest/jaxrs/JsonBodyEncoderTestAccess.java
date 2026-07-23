// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.response.ResponseBodyEncoder;

/**
 * Test-only seam that exposes the package-private framework {@code JsonBodyEncoder} to integration
 * tests outside the {@code dev.vertique.rest.jaxrs} package.
 *
 * <p>The framework's {@link JsonBodyEncoder} is intentionally package-private (it is wired only via
 * {@code RestModule.jsonBodyEncoder()}). The profiled-response ITs in {@code vertique-rest-validation}
 * must exercise the <strong>real</strong> production encoder — not a re-implemented stub — so that the
 * RED→green transition is genuinely driven by the production encoder's behavior. This class lives in
 * the same package name in the test sources, which grants compile- and runtime package-private access
 * to {@link JsonBodyEncoder} across the module boundary, and returns a fresh instance as the public
 * {@link ResponseBodyEncoder} SPI type.
 */
public final class JsonBodyEncoderTestAccess {

    private JsonBodyEncoderTestAccess() {}

    /**
     * Creates a fresh instance of the production {@link JsonBodyEncoder}.
     *
     * @return the framework's JSON body encoder, typed as the public {@link ResponseBodyEncoder} SPI
     */
    public static ResponseBodyEncoder create() {
        return new JsonBodyEncoder();
    }
}
