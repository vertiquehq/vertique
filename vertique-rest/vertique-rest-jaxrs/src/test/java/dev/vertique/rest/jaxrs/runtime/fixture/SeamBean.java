// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import jakarta.ws.rs.QueryParam;

/**
 * Bean-param fixture used by
 * {@link dev.vertique.rest.jaxrs.ParameterExtractorBeanParamSeamTest} to exercise the
 * bean-param model fast-path. The companion {@link SeamBean_BeanParamModel} is present on
 * the test classpath and returns a single {@link dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta}
 * with a sentinel name, so the seam test can assert the generated-model path was taken
 * (a reflective walk over the actual field {@code "filter"} would yield a different name).
 */
public class SeamBean {

    /** An actual JAX-RS-annotated field so the reflective path finds it on the miss scenario. */
    @QueryParam("filter")
    public String filter;
}
