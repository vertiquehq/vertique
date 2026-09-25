// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that the OpenAPI document swagger-core's {@link Reader} builds — the reader the
 * {@code swagger-maven-plugin} build step runs — lists an interface {@code default} resource
 * method exactly once, both when the resource class inherits it and when it overrides it (issue
 * #630). The server routes the same methods, so the published document and the served routes
 * agree.
 */
class InterfaceDefaultMethodOpenApiTest {

    public interface Crud {
        @DELETE
        @Path("/{id}")
        @Operation(operationId = "deleteById")
        default String delete(@PathParam("id") String id) {
            return "deleted " + id;
        }
    }

    @Path("/users")
    public static class UserResource implements Crud {
        @GET
        @Operation(operationId = "listUsers")
        public String list() {
            return "[]";
        }
    }

    @Path("/accounts")
    public static class AccountResource implements Crud {
        @Override
        public String delete(String id) {
            return "account " + id;
        }
    }

    private static List<String> operationIds(PathItem item) {
        return Stream.of(
                        item.getGet(),
                        item.getPut(),
                        item.getPost(),
                        item.getDelete(),
                        item.getOptions(),
                        item.getHead(),
                        item.getPatch(),
                        item.getTrace())
                .filter(Objects::nonNull)
                .map(io.swagger.v3.oas.models.Operation::getOperationId)
                .toList();
    }

    @Test
    @DisplayName("an inherited default method is one DELETE operation next to the class's own GET")
    void inheritedDefault_isDocumentedOnce() {
        OpenAPI openApi = new Reader(new OpenAPI()).read(Set.of(UserResource.class));

        PathItem item = openApi.getPaths().get("/users/{id}");
        assertNotNull(item, "the default route must be documented: " + openApi.getPaths());
        assertEquals(List.of("deleteById"), operationIds(item));
        assertEquals(List.of("listUsers"), operationIds(openApi.getPaths().get("/users")));
    }

    @Test
    @DisplayName("an overridden default method is one DELETE operation, not two")
    void overriddenDefault_isDocumentedOnce() {
        OpenAPI openApi = new Reader(new OpenAPI()).read(Set.of(AccountResource.class));

        PathItem item = openApi.getPaths().get("/accounts/{id}");
        assertNotNull(item, "the overridden route must be documented: " + openApi.getPaths());
        assertEquals(List.of("deleteById"), operationIds(item));
        assertEquals(1, openApi.getPaths().size(), openApi.getPaths().toString());
    }
}
