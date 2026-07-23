// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.security.SecurityPolicy;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the transport-neutral routing contracts introduced for the routing/validation
 * decoupling: {@link RestOperationDescriptor} identity accessors, {@link SecurityRequirement}
 * record equality, and the fluent {@link RouteRegistration#addHandler} contract.
 *
 * <p>These tests pin the shapes frozen in the rest-017 PRD §A.2 so later slices can consume them
 * without re-deriving signatures.
 */
class RestOperationDescriptorTest {

    /**
     * Minimal {@link RestOperationDescriptor} test double with fixed identity fields and empty
     * collections for the remaining contract members.
     */
    private static final class StubDescriptor implements RestOperationDescriptor {

        @Override
        public String operationId() {
            return "listUsers";
        }

        @Override
        public String httpMethod() {
            return "GET";
        }

        @Override
        public String routeTemplate() {
            return "/users";
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
            return new SecurityPolicy.None();
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
    @DisplayName("RestOperationDescriptor stub exposes identity fields with no null non-null contract values")
    void restOperationDescriptorExposesIdentityFields() {
        RestOperationDescriptor op = new StubDescriptor();

        assertEquals("listUsers", op.operationId());
        assertEquals("GET", op.httpMethod());
        assertEquals("/users", op.routeTemplate());
        assertNotNull(op.consumes());
        assertNotNull(op.produces());
        assertNotNull(op.securityPolicy());
        assertNotNull(op.securityRequirementSets());
        assertNotNull(op.methodAnnotations());
        assertNotNull(op.classAnnotations());
        assertEquals(Optional.empty(), op.findAnnotation(Override.class));
    }

    @Test
    @DisplayName("SecurityRequirement records with equal components are equal")
    void securityRequirementRecordEquality() {
        SecurityRequirement a = new SecurityRequirement("bearerAuth", List.of("read:users"));
        SecurityRequirement b = new SecurityRequirement("bearerAuth", List.of("read:users"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("RouteRegistration.addHandler returns the same instance (fluent contract)")
    void routeRegistrationAddHandlerReturnsThis() {
        RouteRegistration reg = new RouteRegistration() {
            @Override
            public RouteRegistration addHandler(Handler<RoutingContext> handler) {
                return this;
            }

            @Override
            public RestOperationDescriptor operation() {
                return new StubDescriptor();
            }
        };

        Handler<RoutingContext> handler = ctx -> {};
        assertSame(reg, reg.addHandler(handler), "addHandler must return the same RouteRegistration");
        assertTrue(reg.operation() instanceof RestOperationDescriptor);
    }
}
