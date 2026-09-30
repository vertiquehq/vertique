// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.vertique.rest.core.interceptor.OperationContext;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.vertx.ext.web.RoutingContext;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the additive evolution of the records and interface that carry the application identity:
 * previous-arity constructors keep compiling and carry {@code null}, new components are appended
 * last, and the descriptor accessor is a nullable default.
 */
@RestRecordEvolutionTest.Marker
class RestRecordEvolutionTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker {}

    private static Annotation marker() {
        return RestRecordEvolutionTest.class.getAnnotation(Marker.class);
    }

    private static List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /** Implements exactly the abstract methods of the baseline descriptor interface. */
    static final class BaselineShapedDescriptor implements RestOperationDescriptor {
        @Override
        public String operationId() {
            return "op";
        }

        @Override
        public String httpMethod() {
            return "GET";
        }

        @Override
        public String routeTemplate() {
            return "/op";
        }

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return null;
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }
    }

    @Test
    void previousArityConstructorsCarryNull() {
        // Given: call sites written against the previous signatures
        RoutingContext routingContext = mock(RoutingContext.class);
        Annotation annotation = marker();

        // When
        MountMeta meta = new MountMeta("jaxrs:/api/*", "/api/*", "openapi.json", Set.of(String.class));
        OperationContext ctx =
                new OperationContext("list", routingContext, null, List.of(annotation), Map.of("k", "v"));

        // Then
        assertNull(meta.applicationName());
        assertEquals("jaxrs:/api/*", meta.mountId());
        assertEquals("/api/*", meta.mountPath());
        assertEquals("openapi.json", meta.openapiPath());
        assertEquals(Set.of(String.class), meta.resourceTypes());

        assertNull(ctx.operation());
        assertEquals("list", ctx.operationId());
        assertSame(routingContext, ctx.routingContext());
        assertEquals(List.of(), ctx.methodAnnotations());
        assertEquals(List.of(annotation), ctx.classAnnotations());
        assertEquals(Map.of("k", "v"), ctx.attributes());
    }

    @Test
    void newComponentsAreAppendedAndCarried() {
        // Given
        RestOperationDescriptor op = new BaselineShapedDescriptor();
        RoutingContext routingContext = mock(RoutingContext.class);
        MountMeta meta = new MountMeta("id", "/api/*", "openapi.json", Set.of(String.class), "mgmt");
        OperationContext ctx = new OperationContext("list", routingContext, List.of(), List.of(), Map.of("k", "v"), op);

        // When
        OperationContext copy = ctx.withAttribute("seen", true);

        // Then: the new components come last
        assertEquals(
                List.of("mountId", "mountPath", "openapiPath", "resourceTypes", "applicationName"),
                componentNames(MountMeta.class));
        assertEquals(
                List.of(
                        "operationId",
                        "routingContext",
                        "methodAnnotations",
                        "classAnnotations",
                        "attributes",
                        "operation"),
                componentNames(OperationContext.class));
        assertEquals("mgmt", meta.applicationName());
        assertSame(op, ctx.operation());

        // Then: the copy carries the same descriptor and the added attribute; the original is unchanged
        assertSame(op, copy.operation());
        assertEquals(Map.of("k", "v", "seen", true), copy.attributes());
        assertEquals(Map.of("k", "v"), ctx.attributes());
        assertFalse(ctx.attributes().containsKey("seen"));
    }

    @Test
    void descriptorApplicationNameDefaultsToNull() throws NoSuchMethodException {
        // Given: an implementation written against the baseline interface
        RestOperationDescriptor descriptor = new BaselineShapedDescriptor();

        // When / Then
        assertNull(descriptor.applicationName());
        var method = RestOperationDescriptor.class.getMethod("applicationName");
        assertTrue(method.isDefault());
        assertTrue(method.isAnnotationPresent(jakarta.annotation.Nullable.class));
    }
}
