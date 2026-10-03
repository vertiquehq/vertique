// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * Interface whose one method carries only a method-level {@code @Parameter(name = "X-Trace-Token",
 * in = HEADER, hidden = true)}; the JAX-RS annotations live on {@link InterfaceHiddenInputsResource},
 * which implements it.
 */
public interface InterfaceHiddenApi {

    /**
     * Reads a trace.
     *
     * @param token header {@code X-Trace-Token} in the implementation, hidden by this declaration
     * @param page  query {@code page} in the implementation, not hidden
     * @return a fixed body
     */
    @Parameter(name = "X-Trace-Token", in = ParameterIn.HEADER, hidden = true)
    String readTrace(String token, String page);
}
