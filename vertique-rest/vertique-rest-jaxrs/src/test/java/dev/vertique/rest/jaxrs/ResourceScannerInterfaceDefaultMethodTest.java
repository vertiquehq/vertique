// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ResourceScanner} treats an annotated interface {@code default} method as a
 * resource method of every class that implements the interface, whether or not the class
 * overrides it (issue #630).
 *
 * <p>Selection follows the Java rules for inherited methods: a method declared by a class in the
 * resource's superclass chain wins over an interface default, and a default in a more specific
 * interface wins over the one it overrides. Each case asserts the route count so a default never
 * yields a second route next to its override.
 */
class ResourceScannerInterfaceDefaultMethodTest {

    // --- Fixtures ---

    /** Contract whose only route is a default method; {@code describe()} is a plain helper. */
    interface Crud {

        /**
         * Deletes by id.
         *
         * @param id the identifier
         * @return a description of the deletion
         */
        @DELETE
        @Path("/{id}")
        @Operation(operationId = "deleteById")
        default String delete(@PathParam("id") String id) {
            return "deleted " + id;
        }

        /**
         * Unannotated helper: never a route.
         *
         * @return a label
         */
        default String describe() {
            return "crud";
        }
    }

    /** Implements {@link Crud} without overriding its default. */
    @Path("/users")
    static class UserResource implements Crud {

        /**
         * Lists users.
         *
         * @return an empty list literal
         */
        @GET
        public String list() {
            return "[]";
        }
    }

    /** Overrides the default without re-declaring any annotation. */
    @Path("/accounts")
    static class AccountResource implements Crud {
        @Override
        public String delete(String id) {
            return "account " + id;
        }
    }

    /** Inherits the {@link Crud} default through its superclass. */
    @Path("/base")
    static class BaseResource implements Crud {}

    /** Declares no interface and no method of its own. */
    static class DerivedResource extends BaseResource {}

    /** Superclass whose private {@code delete(String)} is not inherited, so it cannot shadow the default. */
    static class PrivateHelperBase {
        @SuppressWarnings("unused")
        private String delete(String id) {
            return "private helper " + id;
        }
    }

    /** Inherits the {@link Crud} default next to a superclass's private same-signature helper. */
    @Path("/helpers")
    static class PrivateHelperResource extends PrivateHelperBase implements Crud {}

    /** Overrides the {@link Crud} default with another default and no annotations of its own. */
    interface SoftCrud extends Crud {
        @Override
        default String delete(String id) {
            return "soft " + id;
        }
    }

    /** Implements the more specific {@link SoftCrud}. */
    @Path("/documents")
    static class DocumentResource implements SoftCrud {}

    /**
     * Generic contract. A concrete override inherits its annotations once the type variable is
     * bound through the resource class.
     *
     * @param <I> the identifier type
     */
    interface GenericCrud<I> {

        /**
         * Removes by id.
         *
         * @param id the identifier
         * @return a description of the removal
         */
        @DELETE
        @Path("/{id}")
        default String remove(@PathParam("id") I id) {
            return "generic " + id;
        }
    }

    /** Overrides the generic default with a concrete type; the class declares a bridge. */
    @Path("/orders")
    static class OrderResource implements GenericCrud<String> {
        @Override
        public String remove(String id) {
            return "order " + id;
        }
    }

    /** Overrides the generic default in an interface; javac emits a bridge default here. */
    interface StringCrud extends GenericCrud<String> {
        @Override
        default String remove(String id) {
            return "string " + id;
        }
    }

    /** Implements the interface that carries the bridge default. */
    @Path("/invoices")
    static class InvoiceResource implements StringCrud {}

    /** Generic contract whose default carries security. */
    interface SecuredCrud<I> {

        /**
         * Purges by id.
         *
         * @param id the identifier
         * @return a description of the purge
         */
        @DELETE
        @Path("/{id}")
        @RolesAllowed("admin")
        default String purge(@PathParam("id") I id) {
            return "generic " + id;
        }
    }

    /**
     * Overrides the secured generic default and declares its own verb, so it is a route on its
     * own; the security exists only on the interface.
     */
    @Path("/secured")
    static class SecuredOverrideResource implements SecuredCrud<String> {
        @Override
        @DELETE
        public String purge(String id) {
            return "secured " + id;
        }
    }

    /**
     * Generic superclass with an annotated method.
     *
     * @param <T> the identifier type
     */
    static class GenericBase<T> {

        /**
         * Reads by id.
         *
         * @param id the identifier
         * @return a description of the read
         */
        @GET
        @Path("/{id}")
        public String get(@PathParam("id") T id) {
            return "base " + id;
        }
    }

    /** Overrides the generic superclass method with a concrete type and no annotations. */
    @Path("/derived")
    static class GenericDerivedResource extends GenericBase<String> {
        @Override
        public String get(String id) {
            return "derived " + id;
        }
    }

    /** Default routes with and without method-level security. */
    interface VaultApi {

        /**
         * Purges by id.
         *
         * @param id the identifier
         * @return the identifier
         */
        @DELETE
        @Path("/{id}")
        @RolesAllowed("admin")
        default String purge(@PathParam("id") String id) {
            return id;
        }

        /**
         * Reads by id.
         *
         * @param id the identifier
         * @return the identifier
         */
        @GET
        @Path("/{id}")
        default String read(@PathParam("id") String id) {
            return id;
        }
    }

    /** Class-level security on the resource applies to the inherited defaults. */
    @Path("/vault")
    @RolesAllowed("auditor")
    static class VaultResource implements VaultApi {}

    /** A default carrying a conflicting security pair. */
    interface ConflictingApi {

        /**
         * Peeks by id.
         *
         * @param id the identifier
         * @return the identifier
         */
        @GET
        @Path("/{id}")
        @PermitAll
        @RolesAllowed("admin")
        default String peek(@PathParam("id") String id) {
            return id;
        }
    }

    /** Inherits the conflicting default. */
    @Path("/conflict")
    static class ConflictResource implements ConflictingApi {}

    // --- Helpers ---

    private static List<ResourceMethodMeta> scan(Object resource) {
        return new ResourceScanner(new SecurityPolicyBuilder()).scanResource(resource);
    }

    private static ResourceMethodMeta only(List<ResourceMethodMeta> metas, String httpMethod) {
        List<ResourceMethodMeta> matching =
                metas.stream().filter(m -> m.httpMethod().equals(httpMethod)).toList();
        assertEquals(1, matching.size(), "expected exactly one " + httpMethod + " route in " + metas);
        return matching.get(0);
    }

    private static Object invoke(ResourceMethodMeta meta, Object... args) throws Exception {
        return meta.method().invoke(meta.resourceInstance(), args);
    }

    // --- Tests ---

    @Nested
    @DisplayName("Discovery")
    class Discovery {

        @Test
        @DisplayName("a non-overridden annotated default is a resource method with its verb, path, and operationId")
        void nonOverriddenDefault_isResourceMethod() throws Exception {
            List<ResourceMethodMeta> metas = scan(new UserResource());

            assertEquals(2, metas.size(), "expected list() and the inherited delete(): " + metas);
            ResourceMethodMeta delete = only(metas, "DELETE");
            assertEquals("/users/{id}", delete.path());
            assertEquals("deleteById", delete.operationId());
            assertEquals(Crud.class, delete.method().getDeclaringClass());
            assertEquals(1, delete.params().size());
            assertEquals("id", delete.params().get(0).name());
            assertEquals(
                    ResourceMethodMeta.ParamSource.PATH, delete.params().get(0).source());
            assertEquals(String.class, delete.params().get(0).type());
            assertEquals("deleted 7", invoke(delete, "7"));
        }

        @Test
        @DisplayName("an unannotated default is not a route")
        void unannotatedDefault_isNotRoute() {
            List<ResourceMethodMeta> metas = scan(new UserResource());

            assertTrue(
                    metas.stream().noneMatch(m -> m.method().getName().equals("describe")),
                    "describe() carries no verb and must not be routed: " + metas);
        }

        @Test
        @DisplayName("a class override wins: one route, backed by the class method")
        void classOverride_winsOverDefault() throws Exception {
            List<ResourceMethodMeta> metas = scan(new AccountResource());

            assertEquals(1, metas.size(), "the override must not add a second route: " + metas);
            ResourceMethodMeta delete = only(metas, "DELETE");
            assertEquals("/accounts/{id}", delete.path());
            assertEquals(AccountResource.class, delete.method().getDeclaringClass());
            assertEquals("account 7", invoke(delete, "7"));
        }

        @Test
        @DisplayName("a default inherited through a superclass's interface is a resource method")
        void defaultThroughSuperclassInterface_isResourceMethod() throws Exception {
            List<ResourceMethodMeta> metas = scan(new DerivedResource());

            assertEquals(1, metas.size(), metas.toString());
            ResourceMethodMeta delete = only(metas, "DELETE");
            assertEquals("/base/{id}", delete.path());
            assertEquals("deleted 7", invoke(delete, "7"));
        }

        @Test
        @DisplayName("the most specific interface default wins and inherits the overridden default's annotations")
        void moreSpecificInterfaceDefault_wins() throws Exception {
            List<ResourceMethodMeta> metas = scan(new DocumentResource());

            assertEquals(1, metas.size(), "the overridden default must not add a second route: " + metas);
            ResourceMethodMeta delete = only(metas, "DELETE");
            assertEquals(SoftCrud.class, delete.method().getDeclaringClass());
            assertEquals("/documents/{id}", delete.path());
            assertEquals("deleteById", delete.operationId());
            assertEquals("id", delete.params().get(0).name());
            assertEquals("soft 7", invoke(delete, "7"));
        }

        @Test
        @DisplayName("a superclass's private same-signature method does not shadow the inherited default")
        void privateSuperclassMethod_doesNotShadowDefault() throws Exception {
            List<ResourceMethodMeta> metas = scan(new PrivateHelperResource());

            ResourceMethodMeta delete = only(metas, "DELETE");
            assertEquals(Crud.class, delete.method().getDeclaringClass());
            assertEquals("deleted 7", invoke(delete, "7"));
        }

        @Test
        @DisplayName("a generic default overridden by a class is one route backed by the class method")
        void genericDefaultOverriddenByClass_isOneRouteBackedByTheClass() throws Exception {
            List<ResourceMethodMeta> metas = scan(new OrderResource());

            assertTrue(
                    metas.stream().noneMatch(m -> m.method().getDeclaringClass() == GenericCrud.class),
                    "the class override (via its bridge) must shadow the default: " + metas);
            // remove(String) overrides remove(I) with I bound to String, so it inherits the verb,
            // the path and the parameter binding and is the one route.
            ResourceMethodMeta remove = only(metas, "DELETE");
            assertEquals(OrderResource.class, remove.method().getDeclaringClass());
            assertEquals("/orders/{id}", remove.path());
            assertEquals("id", remove.params().get(0).name());
            assertEquals(
                    ResourceMethodMeta.ParamSource.PATH, remove.params().get(0).source());
            assertEquals(String.class, remove.params().get(0).type());
            assertEquals("order 7", invoke(remove, "7"));
        }

        @Test
        @DisplayName("an override that declares its own verb still inherits the interface's security")
        void overrideWithOwnVerb_inheritsInterfaceSecurity() throws Exception {
            ResourceMethodMeta purge = only(scan(new SecuredOverrideResource()), "DELETE");

            assertEquals(SecuredOverrideResource.class, purge.method().getDeclaringClass());
            SecurityPolicy.Constrained policy =
                    assertInstanceOf(SecurityPolicy.Constrained.class, purge.securityPolicy());
            assertEquals(List.of("admin"), policy.requiredRoles());
            assertEquals("/secured/{id}", purge.path());
            assertEquals("secured 7", invoke(purge, "7"));
        }

        @Test
        @DisplayName("a generic superclass method overridden with a concrete type is routed once")
        void genericSuperclassOverride_isRoutedOnce() throws Exception {
            List<ResourceMethodMeta> metas = scan(new GenericDerivedResource());

            ResourceMethodMeta get = only(metas, "GET");
            assertEquals(1, metas.size(), metas.toString());
            assertEquals(GenericDerivedResource.class, get.method().getDeclaringClass());
            assertEquals("/derived/{id}", get.path());
            assertEquals(String.class, get.params().get(0).type());
            assertEquals("derived 7", invoke(get, "7"));
        }

        @Test
        @DisplayName("an interface bridge default is never routed")
        void interfaceBridgeDefault_isNotRouted() {
            List<ResourceMethodMeta> metas = scan(new InvoiceResource());

            assertTrue(
                    metas.stream()
                            .noneMatch(m -> m.method().isBridge() || m.method().isSynthetic()),
                    "a compiler-generated bridge must not become a route: " + metas);
            assertTrue(
                    metas.stream().noneMatch(m -> m.method().getDeclaringClass() == GenericCrud.class),
                    "the overridden generic default must not be routed: " + metas);
            // The sub-interface default overrides remove(I) with I bound to String: it inherits the
            // verb and path and is the single route.
            ResourceMethodMeta remove = only(metas, "DELETE");
            assertEquals(StringCrud.class, remove.method().getDeclaringClass());
            assertEquals("/invoices/{id}", remove.path());
        }
    }

    @Nested
    @DisplayName("Security")
    class Security {

        @Test
        @DisplayName("method-level @RolesAllowed on a default wins over the resource's class-level roles")
        void methodLevelRolesOnDefault_win() {
            ResourceMethodMeta purge = only(scan(new VaultResource()), "DELETE");

            SecurityPolicy.Constrained policy =
                    assertInstanceOf(SecurityPolicy.Constrained.class, purge.securityPolicy());
            assertEquals(List.of("admin"), policy.requiredRoles());
        }

        @Test
        @DisplayName("the resource's class-level @RolesAllowed applies to an unannotated default route")
        void classLevelRoles_applyToDefault() {
            ResourceMethodMeta read = only(scan(new VaultResource()), "GET");

            SecurityPolicy.Constrained policy =
                    assertInstanceOf(SecurityPolicy.Constrained.class, read.securityPolicy());
            assertEquals(List.of("auditor"), policy.requiredRoles());
        }

        @Test
        @DisplayName("conflicting security annotations on a default are reported as a violation")
        void conflictingSecurityOnDefault_isViolation() {
            SecurityPolicyViolationException ex =
                    assertThrows(SecurityPolicyViolationException.class, () -> scan(new ConflictResource()));

            assertFalse(ex.violations().isEmpty());
            assertEquals(
                    SecurityPolicyViolation.ViolationType.CONFLICTING_SECURITY_ANNOTATIONS,
                    ex.violations().get(0).type());
            assertEquals("peek", ex.violations().get(0).operationId());
        }
    }
}
