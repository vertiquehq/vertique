// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Startup rejection of path templates a regex group cannot carry, of {@code @Context} on a resource
 * member, and of interfaces that disagree about the class-level {@code @Path}. Each is driven through
 * {@link JaxRsRouteRegistrar#registerAll} so the routes of the registered resources are what fail.
 */
class ResourceStartupChecksTest {

    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    private RouteRegistrationException register(Object resource) {
        return assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        new JaxRsRouteRegistrar(),
                        Set.of(resource),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        List.of(),
                        List.of(),
                        null,
                        false,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        null,
                        false));
    }

    private void registerAccepted(Object resource) {
        RegistrarTestSupport.registerAll(
                new JaxRsRouteRegistrar(),
                Set.of(resource),
                router,
                RegistrarTestSupport.TEST_MOUNT_META,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                List.of(),
                "OFF",
                null,
                null,
                null,
                false);
    }

    private static RouteRegistrationViolation only(RouteRegistrationException ex) {
        assertEquals(1, ex.violations().size(), ex.getMessage());
        return ex.violations().get(0);
    }

    // --- Regex path variable names ---

    @Path("/u/{user_id:[0-9]+}")
    @PermitAll
    static class UnderscoreRegexResource {
        @GET
        public Future<String> get(@PathParam("user_id") String id) {
            return Future.succeededFuture(id);
        }
    }

    @Path("/u")
    @PermitAll
    static class HyphenAndDotResource {
        @GET
        @Path("/{a-b:[0-9]+}/{c.d}")
        public Future<String> get(@PathParam("a-b") String ab, @PathParam("c.d") String cd) {
            return Future.succeededFuture(ab);
        }
    }

    @Path("/u/{id:[0-9]+}")
    @PermitAll
    static class ValidRegexResource {
        @GET
        @Path("/{subKey}")
        public Future<String> get(@PathParam("id") String id, @PathParam("subKey") String key) {
            return Future.succeededFuture(id + key);
        }
    }

    @Path("/u/{user_id}")
    @PermitAll
    static class PlainUnderscoreResource {
        @GET
        public Future<String> get(@PathParam("user_id") String id) {
            return Future.succeededFuture(id);
        }
    }

    @Test
    @DisplayName("a regex variable named with an underscore is rejected at startup with a clear message")
    void underscoreRegexName_rejected() {
        RouteRegistrationViolation violation = only(register(new UnderscoreRegexResource()));
        assertEquals(RouteRegistrationViolation.ViolationType.INVALID_PATH_TEMPLATE, violation.type());
        assertTrue(violation.message().contains("'user_id'"), violation.message());
        assertTrue(violation.message().contains("/u/{user_id:[0-9]+}"), violation.message());
    }

    @Test
    @DisplayName("hyphen and dot names are rejected too, including an unconstrained variable in the regex template")
    void hyphenAndDotNames_rejected() {
        RouteRegistrationException ex = register(new HyphenAndDotResource());
        assertEquals(2, ex.violations().size(), ex.getMessage());
        assertTrue(ex.violations().stream().anyMatch(v -> v.message().contains("'a-b'")), ex.getMessage());
        assertTrue(ex.violations().stream().anyMatch(v -> v.message().contains("'c.d'")), ex.getMessage());
    }

    @Test
    @DisplayName("letter-and-digit names in a regex template are registered")
    void letterAndDigitNames_accepted() {
        registerAccepted(new ValidRegexResource());
    }

    @Test
    @DisplayName("a name with an underscore in a template with no regex constraint is registered")
    void underscoreNameWithoutRegex_accepted() {
        registerAccepted(new PlainUnderscoreResource());
    }

    // --- Duplicate variables and malformed constraints ---

    @Path("/o/{id:\\d+}")
    @PermitAll
    static class ClassAndMethodShareNameResource {
        @GET
        @Path("/{id}")
        public Future<String> get(@PathParam("id") String id) {
            return Future.succeededFuture(id);
        }
    }

    @Path("/p/{id}")
    @PermitAll
    static class PlainDuplicateResource {
        @GET
        @Path("/x/{id}")
        public Future<String> get(@PathParam("id") String id) {
            return Future.succeededFuture(id);
        }
    }

    @Path("/m")
    @PermitAll
    static class MalformedConstraintResource {
        @GET
        @Path("/{id:[0-9}")
        public Future<String> get(@PathParam("id") String id) {
            return Future.succeededFuture(id);
        }
    }

    @Test
    @DisplayName(
            "a class and a method path that declare one variable name fail startup naming the method and the variable")
    void duplicateAcrossClassAndMethod_rejected() {
        RouteRegistrationViolation violation = only(register(new ClassAndMethodShareNameResource()));
        assertEquals(RouteRegistrationViolation.ViolationType.INVALID_PATH_TEMPLATE, violation.type());
        assertTrue(violation.message().contains("ClassAndMethodShareNameResource.get()"), violation.message());
        assertTrue(violation.message().contains("'id'"), violation.message());
        assertTrue(violation.message().contains("more than once"), violation.message());
        assertTrue(!violation.message().contains("PatternSyntaxException"), violation.message());
    }

    @Test
    @DisplayName("a duplicate variable in a template with no regex constraint fails startup too")
    void duplicateInPlainTemplate_rejected() {
        RouteRegistrationViolation violation = only(register(new PlainDuplicateResource()));
        assertTrue(violation.message().contains("PlainDuplicateResource.get()"), violation.message());
        assertTrue(violation.message().contains("'id'"), violation.message());
    }

    @Test
    @DisplayName("a malformed regex constraint fails startup naming the method and the variable")
    void malformedConstraint_rejected() {
        RouteRegistrationViolation violation = only(register(new MalformedConstraintResource()));
        assertEquals(RouteRegistrationViolation.ViolationType.INVALID_PATH_TEMPLATE, violation.type());
        assertTrue(violation.message().contains("MalformedConstraintResource.get()"), violation.message());
        assertTrue(violation.message().contains("'id'"), violation.message());
        assertTrue(violation.message().contains("not a valid regular expression"), violation.message());
    }

    // --- @Context on a resource member ---

    @Path("/ctx")
    @PermitAll
    static class ContextFieldResource {
        @Context
        RoutingContext routing;

        @GET
        public Future<String> get() {
            return Future.succeededFuture("");
        }
    }

    @Path("/ctx")
    @PermitAll
    static class ContextConstructorResource {
        ContextConstructorResource(String name, @Context RoutingContext routing) {}

        @GET
        public Future<String> get() {
            return Future.succeededFuture("");
        }
    }

    abstract static class ContextBase {
        @Context
        protected RoutingContext routing;
    }

    @Path("/ctx")
    @PermitAll
    static class ContextSuperclassResource extends ContextBase {
        @GET
        public Future<String> get() {
            return Future.succeededFuture("");
        }
    }

    @Path("/ctx")
    @PermitAll
    static class ContextMethodParameterResource {
        @GET
        public Future<String> get(@Context RoutingContext routing) {
            return Future.succeededFuture("");
        }
    }

    @Test
    @DisplayName("@Context on a resource field is rejected at startup")
    void contextField_rejected() {
        RouteRegistrationViolation violation = only(register(new ContextFieldResource()));
        assertEquals(RouteRegistrationViolation.ViolationType.CONTEXT_ON_RESOURCE_MEMBER, violation.type());
        assertTrue(
                violation.message().contains("@Context on field 'routing' of ContextFieldResource is never injected"),
                violation.message());
    }

    @Test
    @DisplayName("@Context on a resource constructor parameter is rejected at startup")
    void contextConstructorParameter_rejected() {
        RouteRegistrationViolation violation = only(register(new ContextConstructorResource("n", null)));
        assertEquals(RouteRegistrationViolation.ViolationType.CONTEXT_ON_RESOURCE_MEMBER, violation.type());
        assertTrue(violation.message().contains("parameter 2 of a constructor"), violation.message());
    }

    @Test
    @DisplayName("@Context on a superclass field is rejected at startup")
    void contextSuperclassField_rejected() {
        RouteRegistrationViolation violation = only(register(new ContextSuperclassResource()));
        assertEquals(RouteRegistrationViolation.ViolationType.CONTEXT_ON_RESOURCE_MEMBER, violation.type());
        assertTrue(violation.message().contains("field 'routing' of ContextBase"), violation.message());
    }

    @Test
    @DisplayName("@Context on a resource-method parameter is still accepted")
    void contextMethodParameter_accepted() {
        registerAccepted(new ContextMethodParameterResource());
    }

    // --- Conflicting interface paths ---

    @Path("/first")
    interface FirstApi {}

    @Path("/second")
    interface SecondApi {}

    @Path("/first")
    interface SameAsFirstApi {}

    static class ConflictingInterfacesResource implements FirstApi, SecondApi {
        @GET
        public Future<String> get() {
            return Future.succeededFuture("");
        }
    }

    @Path("/own")
    @PermitAll
    static class DirectPathResource implements FirstApi, SecondApi {
        @GET
        public Future<String> get() {
            return Future.succeededFuture("");
        }
    }

    @PermitAll
    static class AgreeingInterfacesResource implements FirstApi, SameAsFirstApi {
        @GET
        public Future<String> get() {
            return Future.succeededFuture("");
        }
    }

    @Test
    @DisplayName("two interfaces with different class-level paths are rejected at startup")
    void conflictingInterfacePaths_rejected() {
        RouteRegistrationViolation violation = only(register(new ConflictingInterfacesResource()));
        assertEquals(RouteRegistrationViolation.ViolationType.CONFLICTING_CLASS_PATH, violation.type());
        assertTrue(violation.message().contains("FirstApi"), violation.message());
        assertTrue(violation.message().contains("SecondApi"), violation.message());
    }

    @Test
    @DisplayName("a path declared on the class settles the interfaces")
    void directPath_accepted() {
        registerAccepted(new DirectPathResource());
    }

    @Test
    @DisplayName("interfaces that agree on the path are accepted")
    void agreeingInterfacePaths_accepted() {
        registerAccepted(new AgreeingInterfacesResource());
    }
}
