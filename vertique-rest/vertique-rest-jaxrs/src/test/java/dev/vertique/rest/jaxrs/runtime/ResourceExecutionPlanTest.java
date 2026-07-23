// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compile-time contract test for {@link ResourceExecutionPlan}: after slice 4 the generated-runtime
 * SPI's {@code extractArguments} takes a {@link BoundRequest}, not a Vert.x {@code ValidatedRequest}.
 *
 * <p>The test is primarily a <em>type</em> assertion: the lambda below only compiles if the second
 * parameter of {@link ResourceExecutionPlan#extractArguments} is {@link BoundRequest}. No
 * {@code io.vertx.openapi.validation.ValidatedRequest} is in scope here.
 */
class ResourceExecutionPlanTest {

    @Test
    @DisplayName("extractArguments signature accepts a BoundRequest (not ValidatedRequest)")
    void resourceExecutionPlanExtractArgumentsSignatureAcceptsBoundRequest() throws Exception {
        // The 2nd parameter being typed as BoundRequest is the load-bearing compile-time assertion.
        ResourceExecutionPlan plan = new ResourceExecutionPlan() {
            @Override
            public Object[] extractArguments(RoutingContext ctx, BoundRequest req, GeneratedJaxRsSupport support) {
                return new Object[0];
            }

            @Override
            public Object invoke(Object resource, Object[] args) {
                return null;
            }
        };

        Object[] args = plan.extractArguments(null, null, null);
        assertEquals(0, args.length);
    }
}
