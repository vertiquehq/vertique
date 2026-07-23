// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextValue;
import dev.vertique.rest.core.request.RequestPreconditions;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestContextTypes}.
 *
 * <p>Verifies the injectability predicate (FR-REST-168), the FQN constant values,
 * and the contents of {@link RestContextTypes#RESERVED_UNSUPPORTED_JAXRS_FQNS}.
 */
class RestContextTypesTest {

    // --- Test fixture: custom ContextValue ---

    /** A minimal test-local {@link ContextValue} record used to verify subtype injectability. */
    record Tenant(String id) implements ContextValue {}

    // --- Test fixture: custom JAX-RS SecurityContext subtype ---

    /**
     * A test-local subinterface of {@link jakarta.ws.rs.core.SecurityContext}.
     *
     * <p>Used to verify that subtypes of the JAX-RS {@code SecurityContext} are NOT injectable
     * (the exact type is required — subtypes are not resolvable by the framework resolver).
     */
    interface CustomJaxRsSecurityContext extends jakarta.ws.rs.core.SecurityContext {}

    // --- isInjectable ---

    @Nested
    @DisplayName("isInjectable")
    class IsInjectable {

        @Test
        @DisplayName("RoutingContext is injectable")
        void routingContextIsInjectable() {
            assertTrue(RestContextTypes.isInjectable(RoutingContext.class));
        }

        @Test
        @DisplayName("jakarta SecurityContext is injectable")
        void jakartaSecurityContextIsInjectable() {
            assertTrue(RestContextTypes.isInjectable(jakarta.ws.rs.core.SecurityContext.class));
        }

        @Test
        @DisplayName("framework SecurityContext (ContextValue subtype) is injectable")
        void frameworkSecurityContextIsInjectable() {
            assertTrue(RestContextTypes.isInjectable(dev.vertique.security.SecurityContext.class));
        }

        @Test
        @DisplayName("custom ContextValue record is injectable")
        void customContextValueIsInjectable() {
            assertTrue(RestContextTypes.isInjectable(Tenant.class));
        }

        @Test
        @DisplayName("jakarta SecurityContext subtype is NOT injectable (exact match required)")
        void jakartaSecurityContextSubtypeIsNotInjectable() {
            // The resolver only handles the exact jakarta.ws.rs.core.SecurityContext type;
            // a custom subtype passes scanner validation but fails at request time — reject it.
            assertFalse(RestContextTypes.isInjectable(CustomJaxRsSecurityContext.class));
        }

        @Test
        @DisplayName("RequestPreconditions is NOT injectable")
        void requestPreconditionsIsNotInjectable() {
            assertFalse(RestContextTypes.isInjectable(RequestPreconditions.class));
        }

        @Test
        @DisplayName("String is not injectable")
        void stringIsNotInjectable() {
            assertFalse(RestContextTypes.isInjectable(String.class));
        }

        @Test
        @DisplayName("null is not injectable")
        void nullIsNotInjectable() {
            assertFalse(RestContextTypes.isInjectable(null));
        }
    }

    // --- RESERVED_UNSUPPORTED_JAXRS_FQNS ---

    @Nested
    @DisplayName("RESERVED_UNSUPPORTED_JAXRS_FQNS")
    class ReservedUnsupportedJaxrsFqns {

        @Test
        @DisplayName("contains jakarta.ws.rs.core.UriInfo")
        void containsUriInfo() {
            assertTrue(RestContextTypes.RESERVED_UNSUPPORTED_JAXRS_FQNS.contains("jakarta.ws.rs.core.UriInfo"));
        }

        @Test
        @DisplayName("contains jakarta.ws.rs.core.HttpHeaders")
        void containsHttpHeaders() {
            assertTrue(RestContextTypes.RESERVED_UNSUPPORTED_JAXRS_FQNS.contains("jakarta.ws.rs.core.HttpHeaders"));
        }
    }
}
