// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a method declared by a superclass inherits JAX-RS annotations from interfaces the
 * resource class implements (vertiquehq/vertique-dev#636).
 *
 * <p>Jakarta REST treats the resource-class member as implementing the interface method; the
 * reflective scanner must therefore walk the resource class's interfaces, not only the
 * superclass's.
 */
class ResourceScannerSuperclassInterfaceAnnotationTest {

    interface Crud {
        @DELETE
        @Path("/{id}")
        @Operation(operationId = "deleteById")
        String delete(@PathParam("id") @DefaultValue("0") String id);

        @GET
        @Path("/{id}")
        @Operation(operationId = "getById")
        String get(@PathParam("id") String id);
    }

    /** Declares the routed methods but implements no JAX-RS interface. */
    static class Base {
        public String delete(String id) {
            return "deleted " + id;
        }

        public String get(String id) {
            return "got " + id;
        }
    }

    @Path("/items")
    static class ItemResource extends Base implements Crud {}

    @Test
    @DisplayName(
            "superclass method inherits HTTP verb, path, operationId, and param annotations from resource interfaces")
    void superclassMethodInheritsInterfaceAnnotations() {
        List<ResourceMethodMeta> metas = new JaxRsRouteRegistrar().scanResource(new ItemResource());

        assertEquals(2, metas.size(), metas::toString);

        ResourceMethodMeta delete = byOperationId(metas, "deleteById");
        assertEquals("DELETE", delete.httpMethod());
        assertEquals("/items/{id}", delete.path());
        assertEquals(Base.class, delete.method().getDeclaringClass());
        assertEquals(1, delete.params().size());
        assertEquals("id", delete.params().getFirst().name());
        assertEquals(
                ResourceMethodMeta.ParamSource.PATH, delete.params().getFirst().source());
        assertEquals("0", delete.params().getFirst().defaultValue());

        ResourceMethodMeta get = byOperationId(metas, "getById");
        assertEquals("GET", get.httpMethod());
        assertEquals("/items/{id}", get.path());
        assertEquals(Base.class, get.method().getDeclaringClass());
    }

    @Test
    @DisplayName("methodAnnotations list includes the interface verb for a superclass-declared method")
    void methodAnnotationsIncludeInterfaceVerb() {
        List<ResourceMethodMeta> metas = new JaxRsRouteRegistrar().scanResource(new ItemResource());
        ResourceMethodMeta delete = byOperationId(metas, "deleteById");
        assertTrue(
                delete.methodAnnotations().stream().anyMatch(a -> a instanceof DELETE),
                "merged methodAnnotations must include @DELETE from Crud");
        assertTrue(
                delete.methodAnnotations().stream().anyMatch(a -> a instanceof Path),
                "merged methodAnnotations must include @Path from Crud");
    }

    private static ResourceMethodMeta byOperationId(List<ResourceMethodMeta> metas, String operationId) {
        return metas.stream()
                .filter(m -> operationId.equals(m.operationId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + operationId + " in " + metas));
    }
}
