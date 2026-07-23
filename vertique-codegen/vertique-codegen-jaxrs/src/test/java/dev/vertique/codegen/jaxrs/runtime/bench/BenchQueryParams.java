// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.runtime.bench;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

/**
 * Bean-param composite used by {@link BenchResource#query(BenchQueryParams)} in the
 * benchmark fixture. Carries a representative set of scalar query params to make the
 * bean-param materialisation phase non-trivial.
 */
public class BenchQueryParams {

    /** Filter term. */
    @QueryParam("filter")
    public String filter;

    /** Sort field; defaults to {@code "id"}. */
    @QueryParam("sort")
    @DefaultValue("id")
    public String sort;

    /** Ascending sort flag. */
    @QueryParam("asc")
    @DefaultValue("true")
    public boolean ascending;

    /** No-arg constructor for reflective instantiation. */
    public BenchQueryParams() {}
}
