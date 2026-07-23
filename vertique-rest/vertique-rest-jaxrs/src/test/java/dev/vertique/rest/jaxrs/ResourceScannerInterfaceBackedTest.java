// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.validation.ValidateWith;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ResourceScanner} honours JAX-RS annotations declared on interfaces when the
 * concrete resource implementation carries no direct annotations of its own (closed issue #31).
 *
 * <p>The following annotation kinds are covered:
 *
 * <ul>
 *   <li>Class-level {@code @Path} sourced from the interface</li>
 *   <li>Method-level {@code @GET} and {@code @Path} sourced from the interface</li>
 *   <li>{@code @Operation(operationId)} sourced from the interface</li>
 *   <li>{@code @Consumes} / {@code @Produces} sourced from the interface (method overrides class)</li>
 *   <li>Method-level {@code @RolesAllowed} sourced from the interface</li>
 *   <li>Class-level {@code @PermitAll} sourced from the interface</li>
 *   <li>Parameter {@code @PathParam} and {@code @DefaultValue} sourced from the interface</li>
 *   <li>Direct annotation on the concrete class overriding the interface annotation</li>
 *   <li>Interface-declared {@code @ValidateWith} — MEDIUM-3 regression</li>
 * </ul>
 */
class ResourceScannerInterfaceBackedTest {

    // --- Test fixtures ---

    /**
     * Interface that declares all JAX-RS routing and OpenAPI annotations. The concrete
     * implementation below carries none of them, so the scanner must source everything from here.
     */
    @Path("/users")
    @Consumes("application/json")
    @Produces("application/json")
    @RolesAllowed("admin")
    interface UsersApi {

        /**
         * Returns a user by ID.
         *
         * @param id the user identifier
         * @return the user representation
         */
        @GET
        @Path("/{id}")
        @Operation(operationId = "getUser")
        @Consumes("application/json")
        @Produces("application/xml")
        @RolesAllowed("admin")
        String getUser(@PathParam("id") @DefaultValue("0") String id);
    }

    /** Concrete implementation: zero direct JAX-RS annotations. */
    static class UsersResource implements UsersApi {
        @Override
        public String getUser(String id) {
            return id;
        }
    }

    // --- Interface with class-level @PermitAll ---

    /**
     * Interface declaring class-level {@code @PermitAll} and a simple method.
     */
    @Path("/public")
    @PermitAll
    interface PublicApi {

        /**
         * @return greeting
         */
        @GET
        String hello();
    }

    /** Concrete implementation carrying no direct annotations. */
    static class PublicResource implements PublicApi {
        @Override
        public String hello() {
            return "hello";
        }
    }

    // --- Interface with @Path only on the class, method has no @Path ---

    /**
     * Interface with a verb but no method-level {@code @Path}.
     */
    @Path("/items")
    interface ItemsApi {

        /**
         * @return all items
         */
        @GET
        @Operation(operationId = "listItems")
        List<String> list();
    }

    /** Concrete implementation without any direct annotations. */
    static class ItemsResource implements ItemsApi {
        @Override
        public List<String> list() {
            return List.of();
        }
    }

    // --- Concrete class @Path overrides interface @Path ---

    /**
     * Interface declaring class-level {@code @Path("/interface-path")}.
     */
    @Path("/interface-path")
    interface OverrideApi {

        /**
         * @return value
         */
        @GET
        @Operation(operationId = "overrideOp")
        String get();
    }

    /**
     * Concrete class declares its own {@code @Path("/concrete-path")} — must override the
     * interface declaration.
     */
    @Path("/concrete-path")
    static class OverrideResource implements OverrideApi {
        @Override
        public String get() {
            return "concrete";
        }
    }

    // --- Helper ---

    private ResourceScanner scanner() {
        return new ResourceScanner(new SecurityPolicyBuilder());
    }

    // --- Tests ---

    @Nested
    @DisplayName("Interface-declared routing annotations")
    class InterfaceDeclaredRouting {

        @Test
        @DisplayName("class-level @Path from interface is used as base path")
        void classLevelPath_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size(), "Expected one endpoint discovered");
            assertTrue(
                    metas.get(0).path().startsWith("/users"),
                    "Path should start with the interface @Path('/users') but was: "
                            + metas.get(0).path());
        }

        @Test
        @DisplayName("method-level @GET from interface is used as HTTP verb")
        void methodLevelGet_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size());
            assertEquals("GET", metas.get(0).httpMethod());
        }

        @Test
        @DisplayName("method-level @Path from interface is appended to class-level path")
        void methodLevelPath_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size());
            assertEquals("/users/{id}", metas.get(0).path());
        }

        @Test
        @DisplayName("@Operation(operationId) from interface is used as operationId")
        void operationId_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size());
            assertEquals("getUser", metas.get(0).operationId());
        }
    }

    @Nested
    @DisplayName("Interface-declared media type annotations")
    class InterfaceDeclaredMediaTypes {

        @Test
        @DisplayName("method-level @Produces from interface overrides class-level @Produces")
        void produces_methodOverridesClass_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size());
            // Method-level declares application/xml; class-level declares application/json.
            // Method wins.
            assertEquals(List.of("application/xml"), metas.get(0).mediaTypes().produces());
        }

        @Test
        @DisplayName("method-level @Consumes from interface overrides class-level @Consumes")
        void consumes_methodOverridesClass_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size());
            // Both method and class declare application/json — method wins, value same.
            assertEquals(List.of("application/json"), metas.get(0).mediaTypes().consumes());
        }

        @Test
        @DisplayName("class-level-only @Consumes and @Produces from interface are used when no method-level override")
        void classLevelMediaTypes_fromInterface_noMethodOverride() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new PublicResource());
            assertEquals(1, metas.size(), "Expected one endpoint discovered");
            // PublicApi declares no @Consumes/@Produces at all — both lists should be empty
            assertTrue(metas.get(0).mediaTypes().consumes().isEmpty(), "Expected empty consumes for PublicResource");
            assertTrue(metas.get(0).mediaTypes().produces().isEmpty(), "Expected empty produces for PublicResource");
        }
    }

    @Nested
    @DisplayName("Interface-declared security annotations")
    class InterfaceDeclaredSecurity {

        @Test
        @DisplayName("method-level @RolesAllowed from interface resolves to Constrained policy")
        void rolesAllowed_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size());
            SecurityPolicy policy = metas.get(0).securityPolicy();
            assertInstanceOf(
                    SecurityPolicy.Constrained.class,
                    policy,
                    "Expected Constrained policy from @RolesAllowed('admin') on interface");
            SecurityPolicy.Constrained constrained = (SecurityPolicy.Constrained) policy;
            assertEquals(List.of("admin"), constrained.requiredRoles());
        }

        @Test
        @DisplayName("class-level @PermitAll from interface resolves to PermitAll policy")
        void permitAll_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new PublicResource());
            assertEquals(1, metas.size(), "Expected one endpoint discovered");
            assertInstanceOf(
                    SecurityPolicy.PermitAll.class,
                    metas.get(0).securityPolicy(),
                    "Expected PermitAll policy from interface class-level @PermitAll");
        }
    }

    @Nested
    @DisplayName("Interface-declared parameter annotations")
    class InterfaceDeclaredParameters {

        @Test
        @DisplayName("@PathParam from interface is resolved as PATH param with correct name")
        void pathParam_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size());
            List<ResourceMethodMeta.ParamMeta> params = metas.get(0).params();
            assertEquals(1, params.size(), "Expected exactly one parameter");
            ResourceMethodMeta.ParamMeta param = params.get(0);
            assertEquals(ResourceMethodMeta.ParamSource.PATH, param.source());
            assertEquals("id", param.name());
            assertEquals(String.class, param.type());
        }

        @Test
        @DisplayName("@DefaultValue from interface is propagated to ParamMeta")
        void defaultValue_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size());
            ResourceMethodMeta.ParamMeta param = metas.get(0).params().get(0);
            assertEquals("0", param.defaultValue(), "@DefaultValue('0') from interface must be honoured");
        }

        @Test
        @DisplayName("merged annotations array in ParamMeta includes interface-declared annotations")
        void mergedParamAnnotations_includeInterfaceAnnotations() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new UsersResource());
            assertEquals(1, metas.size());
            ResourceMethodMeta.ParamMeta param = metas.get(0).params().get(0);
            assertNotNull(param.annotationsLazy().get(), "annotations array must not be null");
            boolean hasPathParam = false;
            boolean hasDefaultValue = false;
            for (java.lang.annotation.Annotation ann : param.annotationsLazy().get()) {
                if (ann instanceof PathParam) hasPathParam = true;
                if (ann instanceof DefaultValue) hasDefaultValue = true;
            }
            assertTrue(hasPathParam, "Merged annotations must include @PathParam from interface");
            assertTrue(hasDefaultValue, "Merged annotations must include @DefaultValue from interface");
        }
    }

    @Nested
    @DisplayName("Interface-declared @Path on class only (no method-level @Path)")
    class InterfaceClassPathOnly {

        @Test
        @DisplayName("class-level @Path from interface with no method-level @Path produces correct path")
        void classOnlyPath_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new ItemsResource());
            assertEquals(1, metas.size(), "Expected one endpoint discovered");
            assertEquals("/items", metas.get(0).path(), "Path should be /items with no method-level @Path");
        }

        @Test
        @DisplayName("@Operation(operationId='listItems') from interface is used")
        void operationId_listItems_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new ItemsResource());
            assertEquals(1, metas.size());
            assertEquals("listItems", metas.get(0).operationId());
        }
    }

    @Nested
    @DisplayName("Direct annotation on concrete class overrides interface annotation")
    class ConcreteOverridesInterface {

        @Test
        @DisplayName("concrete class @Path('/concrete-path') wins over interface @Path('/interface-path')")
        void concretePath_overridesInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new OverrideResource());
            assertEquals(1, metas.size(), "Expected one endpoint discovered");
            assertEquals(
                    "/concrete-path",
                    metas.get(0).path(),
                    "Direct @Path on concrete class must override interface @Path");
        }
    }

    @Nested
    @DisplayName("No @Path anywhere — resource skipped")
    class NoPathAnywhere {

        /** Interface with no @Path. Concrete class has no @Path either. */
        interface NakedApi {
            @GET
            String get();
        }

        static class NakedResource implements NakedApi {
            @Override
            public String get() {
                return "naked";
            }
        }

        @Test
        @DisplayName("resource with no @Path in hierarchy produces empty scan result")
        void noPath_returnsEmpty() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new NakedResource());
            assertTrue(metas.isEmpty(), "Resource with no @Path anywhere should be skipped");
        }
    }

    // --- @ValidateWith regression (MEDIUM-3) ---

    /**
     * Marker interface used as a validation group in {@link ValidateWithApi}.
     * Plain Java interface — no Jakarta Validation dependency required.
     */
    interface CreateGroup {}

    /**
     * Interface declaring {@code @ValidateWith(CreateGroup.class)} on the method.
     * The concrete implementation below carries no direct annotation.
     */
    @Path("/validated")
    @PermitAll
    interface ValidateWithApi {

        /**
         * @param body request body
         * @return result
         */
        @ValidateWith(CreateGroup.class)
        @jakarta.ws.rs.POST
        String create(String body);
    }

    /** Concrete implementation carrying no direct {@code @ValidateWith}. */
    static class ValidatedResource implements ValidateWithApi {
        @Override
        public String create(String body) {
            return body;
        }
    }

    @Nested
    @DisplayName("Interface-declared @ValidateWith — MEDIUM-3 regression")
    class InterfaceDeclaredValidateWith {

        @Test
        @DisplayName("@ValidateWith declared on interface method is honoured by reflective scanner")
        void validateWith_fromInterface() {
            List<ResourceMethodMeta> metas = scanner().scanResource(new ValidatedResource());
            assertEquals(1, metas.size(), "Expected one endpoint");
            Class<?>[] groups = metas.get(0).validationGroups();
            assertNotNull(groups, "@ValidateWith from interface must yield non-null validationGroups");
            assertEquals(1, groups.length, "Expected exactly one validation group");
            assertEquals(
                    CreateGroup.class, groups[0], "Validation group must be CreateGroup as declared on the interface");
        }
    }
}
