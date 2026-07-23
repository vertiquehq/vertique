// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsSupport;
import dev.vertique.rest.jaxrs.runtime.ResourceExecutionPlan;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ResourceMethodMeta} correctly carries (or defaults) the optional
 * {@link ResourceExecutionPlan} field added in CG-010 slice 2.
 *
 * <p>Two facets are pinned:
 * <ol>
 *   <li>The convenience constructor that omits {@code executionPlan} defaults it to {@code null},
 *       so every existing call site (e.g. {@code ResourceScanner.scanResource}) keeps producing
 *       reflective-path metadata without source changes.</li>
 *   <li>The full constructor stores the supplied plan instance verbatim and exposes it via
 *       {@link ResourceMethodMeta#executionPlan()}.</li>
 * </ol>
 */
class ResourceMethodMetaExecutionPlanFieldTest {

    @Test
    @DisplayName("convenience constructor (no plan) defaults executionPlan() to null")
    void convenienceCtorDefaultsToNull() throws Exception {
        ResourceMethodMeta meta = newMeta(null);
        assertNull(meta.executionPlan(), "Expected null executionPlan from the convenience constructor");
    }

    @Test
    @DisplayName("full constructor preserves the supplied execution plan")
    void fullCtorPreservesPlan() throws Exception {
        ResourceExecutionPlan plan = new NoopPlan();
        ResourceMethodMeta meta = newMeta(plan);
        assertNotNull(meta.executionPlan());
        assertSame(plan, meta.executionPlan(), "The plan instance must be returned unchanged");
    }

    private static ResourceMethodMeta newMeta(ResourceExecutionPlan plan) throws NoSuchMethodException {
        Method method = SampleResource.class.getDeclaredMethod("noop");
        if (plan == null) {
            return new ResourceMethodMeta(
                    new SampleResource(),
                    method,
                    "noop",
                    "GET",
                    "/noop",
                    List.of(),
                    Void.class,
                    false,
                    true,
                    new SecurityPolicy.None(),
                    ResourceMethodMeta.MediaTypes.EMPTY,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }
        return new ResourceMethodMeta(
                new SampleResource(),
                method,
                "noop",
                "GET",
                "/noop",
                List.of(),
                Void.class,
                false,
                true,
                new SecurityPolicy.None(),
                ResourceMethodMeta.MediaTypes.EMPTY,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                plan);
    }

    static class SampleResource {
        public void noop() {}
    }

    static class NoopPlan implements ResourceExecutionPlan {
        @Override
        public Object[] extractArguments(RoutingContext ctx, BoundRequest request, GeneratedJaxRsSupport support) {
            return new Object[0];
        }

        @Override
        public Object invoke(Object resource, Object[] args) {
            return null;
        }
    }
}
