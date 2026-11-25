// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that the document swagger-core's {@link Reader} builds, the reader the {@code swagger-maven-plugin}
 * build step runs, publishes the security of an operation as the runtime enforces it: the method-level
 * requirements when there are any, otherwise the class-level ones, otherwise none, and an empty
 * {@code @SecurityRequirements({})} on the resource class or its own method with neither level present
 * as an explicit empty list. Swagger's own reader would list the class-level requirements next to the
 * method's.
 */
class SecurityRequirementsExtensionTest {

    @Path("/classAndMethod")
    @SecurityRequirement(name = "classScheme")
    public static class ClassAndMethodResource {
        @GET
        @Path("/both")
        @SecurityRequirement(name = "methodScheme")
        public String both() {
            return "";
        }

        @GET
        @Path("/two")
        @SecurityRequirements({
            @SecurityRequirement(name = "methodA"),
            @SecurityRequirement(
                    name = "methodB",
                    scopes = {"read"})
        })
        public String two() {
            return "";
        }

        @GET
        @Path("/viaOperation")
        @io.swagger.v3.oas.annotations.Operation(security = @SecurityRequirement(name = "operationScheme"))
        public String viaOperation() {
            return "";
        }

        @GET
        @Path("/classOnly")
        public String classOnly() {
            return "";
        }

        @GET
        @Path("/emptyMarker")
        @SecurityRequirements({})
        public String emptyMarker() {
            return "";
        }
    }

    public interface SecuredApi {
        @GET
        @Path("/declared")
        @SecurityRequirement(name = "interfaceScheme")
        String declared();
    }

    @Path("/implementing")
    @SecurityRequirement(name = "classScheme")
    public static class ImplementingResource implements SecuredApi {
        @Override
        public String declared() {
            return "";
        }
    }

    @Path("/none")
    public static class NoSecurityResource {
        @GET
        @Path("/plain")
        public String plain() {
            return "";
        }

        @GET
        @Path("/marked")
        @SecurityRequirements({})
        public String marked() {
            return "";
        }

        @GET
        @Path("/own")
        @SecurityRequirement(name = "methodScheme")
        public String own() {
            return "";
        }
    }

    @Path("/publicClass")
    @SecurityRequirements({})
    public static class PublicClassResource {
        @GET
        @Path("/plain")
        public String plain() {
            return "";
        }

        @GET
        @Path("/own")
        @SecurityRequirement(name = "methodScheme")
        public String own() {
            return "";
        }
    }

    private static OpenAPI read(Class<?>... resources) {
        return new Reader(new OpenAPI()).read(Set.of(resources));
    }

    private static Operation get(OpenAPI openApi, String path) {
        assertNotNull(
                openApi.getPaths().get(path),
                () -> "missing " + path + " in " + openApi.getPaths().keySet());
        return openApi.getPaths().get(path).getGet();
    }

    private static List<String> schemes(Operation operation) {
        return operation.getSecurity().stream()
                .map(requirement -> String.join("+", requirement.keySet()))
                .toList();
    }

    @Test
    @DisplayName("a method-level requirement replaces the class-level one instead of joining it")
    void methodLevelReplacesClassLevel() {
        OpenAPI openApi = read(ClassAndMethodResource.class);

        assertEquals(List.of("methodScheme"), schemes(get(openApi, "/classAndMethod/both")));
    }

    @Test
    @DisplayName("several method-level requirements are all kept, with their scopes, and the class level is dropped")
    void severalMethodLevelRequirementsReplaceClassLevel() {
        Operation operation = get(read(ClassAndMethodResource.class), "/classAndMethod/two");

        assertEquals(List.of("methodA", "methodB"), schemes(operation));
        assertEquals(List.of("read"), operation.getSecurity().get(1).get("methodB"));
    }

    @Test
    @DisplayName("a requirement declared through @Operation(security) replaces the class-level one too")
    void operationSecurityReplacesClassLevel() {
        assertEquals(
                List.of("operationScheme"),
                schemes(get(read(ClassAndMethodResource.class), "/classAndMethod/viaOperation")));
    }

    @Test
    @DisplayName("an operation without its own requirement keeps the class-level one")
    void classLevelApplies() {
        assertEquals(
                List.of("classScheme"), schemes(get(read(ClassAndMethodResource.class), "/classAndMethod/classOnly")));
    }

    @Test
    @DisplayName("a requirement on the interface method the resource implements replaces the class-level one")
    void interfaceDeclaredRequirementReplacesClassLevel() {
        assertEquals(
                List.of("interfaceScheme"), schemes(get(read(ImplementingResource.class), "/implementing/declared")));
    }

    @Test
    @DisplayName("an empty marker on a method does not override a class-level requirement, as at runtime")
    void emptyMarkerDoesNotBeatClassLevel() {
        assertEquals(
                List.of("classScheme"),
                schemes(get(read(ClassAndMethodResource.class), "/classAndMethod/emptyMarker")));
    }

    @Test
    @DisplayName("an empty marker with no requirement at any level publishes an empty security list")
    void emptyMarkerIsPublic() {
        OpenAPI openApi = read(NoSecurityResource.class, PublicClassResource.class);

        assertEquals(List.of(), get(openApi, "/none/marked").getSecurity());
        assertEquals(List.of(), get(openApi, "/publicClass/plain").getSecurity());
    }

    @Test
    @DisplayName("an operation with no requirement at any level and no marker publishes no security member")
    void noSecurityStaysAbsent() {
        assertNull(get(read(NoSecurityResource.class), "/none/plain").getSecurity());
    }

    @Test
    @DisplayName("a method-level requirement still wins over a public class marker")
    void methodLevelBeatsClassMarker() {
        OpenAPI openApi = read(NoSecurityResource.class, PublicClassResource.class);

        assertEquals(List.of("methodScheme"), schemes(get(openApi, "/none/own")));
        assertEquals(List.of("methodScheme"), schemes(get(openApi, "/publicClass/own")));
    }
}
