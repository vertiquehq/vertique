// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.exception.ConfigurationException;

/**
 * A request refused because an application {@code RequestBodyDecoder} claimed a body on a route that declares
 * a canonicalizer or sanitizer chain, which the decoder's result would never pass through. It is the
 * request-time backstop of the startup check; the default mapper answers it with a generic 500 and logs it as
 * a handled mapping, so a misconfigured route does not write an unhandled-error stack per request.
 */
final class DecoderChainRefusedException extends ConfigurationException {

    private static final long serialVersionUID = 1L;

    DecoderChainRefusedException(String message) {
        super(message);
    }
}
