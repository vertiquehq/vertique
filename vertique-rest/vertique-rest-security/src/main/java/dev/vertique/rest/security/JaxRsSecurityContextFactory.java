// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.SecurityContext;

/**
 * Factory for creating {@code jakarta.ws.rs.core.SecurityContext} instances
 * that bridge to the framework's {@link SecurityContext}.
 *
 * <p>Implemented by {@link JaxRsSecurityContext} and provided via {@link SecurityModule}.
 * Used by {@link HolderBackedSecurityRuntime} for the
 * {@link dev.vertique.rest.core.security.SecurityRuntime#toJaxRs} bridge.
 */
@FunctionalInterface
public interface JaxRsSecurityContextFactory {
    /**
     * Creates a JAX-RS SecurityContext from the framework SecurityContext.
     *
     * @param securityContext the framework security context (may be null for anonymous)
     * @param secure          whether the request was made over a secure channel (HTTPS)
     * @return a JAX-RS SecurityContext instance
     */
    jakarta.ws.rs.core.SecurityContext create(SecurityContext securityContext, boolean secure);
}
