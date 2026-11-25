// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.jaxrs.SecuritySchemeAnnotationScanner.DocumentSecurity;
import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.oas.annotations.security.SecurityRequirementEntry;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that the security the build-time document lists for an operation is the security the runtime
 * enforces. The build-time side is swagger-core's {@link Reader}, the reader the {@code
 * swagger-maven-plugin} runs, with the {@code vertique-rest-openapi-plugin} extensions on the
 * classpath as they are on the plugin's; the runtime side is {@link
 * SecuritySchemeAnnotationScanner}. A method-level requirement replaces the class-level one on both
 * sides, several method-level requirements and an AND-group keep their shape, and an operation with
 * no requirement at any level lists none, because the runtime then applies the document level.
 */
class BuildTimeSecurityParityTest {

    @Path("/classLevel")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "classScheme")
    public static class ClassLevelResource {
        @GET
        @Path("/inherits")
        public String inherits() {
            return "";
        }

        @GET
        @Path("/overrides")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "methodScheme", scopes = "read")
        public String overrides() {
            return "";
        }

        @GET
        @Path("/alternatives")
        @SecurityRequirements({
            @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "methodA"),
            @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "methodB", scopes = "write")
        })
        public String alternatives() {
            return "";
        }

        @GET
        @Path("/combined")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(
                combine = {
                    @SecurityRequirementEntry(name = "first"),
                    @SecurityRequirementEntry(name = "second", scopes = "admin")
                })
        public String combined() {
            return "";
        }

        @GET
        @Path("/viaOperation")
        @io.swagger.v3.oas.annotations.Operation(
                security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "operationScheme"))
        public String viaOperation() {
            return "";
        }

        @GET
        @Path("/marker")
        @SecurityRequirements({})
        public String marker() {
            return "";
        }
    }

    public interface SecuredApi {
        @GET
        @Path("/declared")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "interfaceScheme")
        String declared();
    }

    @Path("/implementing")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "classScheme")
    public static class ImplementingResource implements SecuredApi {
        @Override
        public String declared() {
            return "";
        }
    }

    @Path("/noLevel")
    public static class NoLevelResource {
        @GET
        @Path("/plain")
        public String plain() {
            return "";
        }

        @GET
        @Path("/own")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "methodScheme")
        public String own() {
            return "";
        }

        @GET
        @Path("/open")
        @SecurityRequirements({})
        public String open() {
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
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "methodScheme")
        public String own() {
            return "";
        }
    }

    private static List<List<List<String>>> runtime(Class<?> resource, String methodName) throws Exception {
        Method method = resource.getMethod(methodName);
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(resource);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                resource.getDeclaredConstructor().newInstance(),
                method,
                methodName,
                "GET",
                "/" + methodName,
                List.of(),
                String.class,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                methodAnnotations,
                classAnnotations,
                List.of(),
                List.of());
        List<List<List<String>>> sets = new ArrayList<>();
        for (SecurityRequirementSet set :
                SecuritySchemeAnnotationScanner.effectiveRequirements(meta, DocumentSecurity.NONE)) {
            List<List<String>> schemes = new ArrayList<>();
            for (SecurityRequirement scheme : set.schemes()) {
                schemes.add(shape(scheme.schemeName(), scheme.scopes()));
            }
            sets.add(schemes);
        }
        return sets;
    }

    private static List<List<List<String>>> buildTime(OpenAPI openApi, String path) {
        assertNotNull(openApi.getPaths().get(path), () -> "missing " + path);
        Operation operation = openApi.getPaths().get(path).getGet();
        List<List<List<String>>> sets = new ArrayList<>();
        if (operation.getSecurity() != null) {
            for (io.swagger.v3.oas.models.security.SecurityRequirement requirement : operation.getSecurity()) {
                List<List<String>> schemes = new ArrayList<>();
                requirement.forEach((name, scopes) -> schemes.add(shape(name, scopes)));
                sets.add(schemes);
            }
        }
        return sets;
    }

    private static List<String> shape(String name, List<String> scopes) {
        List<String> shape = new ArrayList<>();
        shape.add(name);
        shape.addAll(scopes);
        return shape;
    }

    @Test
    @DisplayName("the build-time document lists each operation's security as the runtime enforces it")
    void buildTimeSecurityMatchesRuntime() throws Exception {
        OpenAPI openApi = new Reader(new OpenAPI())
                .read(Set.of(
                        ClassLevelResource.class,
                        NoLevelResource.class,
                        PublicClassResource.class,
                        ImplementingResource.class));
        String[][] operations = {
            {"/classLevel/inherits", "inherits"},
            {"/classLevel/overrides", "overrides"},
            {"/classLevel/alternatives", "alternatives"},
            {"/classLevel/combined", "combined"},
            {"/classLevel/viaOperation", "viaOperation"},
            {"/classLevel/marker", "marker"},
        };
        for (String[] operation : operations) {
            assertEquals(
                    runtime(ClassLevelResource.class, operation[1]), buildTime(openApi, operation[0]), operation[0]);
        }
        assertEquals(
                runtime(ImplementingResource.class, "declared"),
                buildTime(openApi, "/implementing/declared"),
                "/implementing/declared");
        // An empty list and an absent member differ in OpenAPI: the first opts out of a document level.
        assertNull(openApi.getPaths().get("/noLevel/plain").getGet().getSecurity(), "no requirement: no member");
        assertEquals(
                List.of(),
                openApi.getPaths().get("/noLevel/open").getGet().getSecurity(),
                "marker: explicit empty list");
        assertEquals(
                List.of(),
                openApi.getPaths().get("/publicClass/plain").getGet().getSecurity(),
                "class marker: explicit empty list");
        assertEquals(runtime(NoLevelResource.class, "plain"), buildTime(openApi, "/noLevel/plain"), "/noLevel/plain");
        assertEquals(runtime(NoLevelResource.class, "own"), buildTime(openApi, "/noLevel/own"), "/noLevel/own");
        assertEquals(runtime(NoLevelResource.class, "open"), buildTime(openApi, "/noLevel/open"), "/noLevel/open");
        assertEquals(
                runtime(PublicClassResource.class, "plain"),
                buildTime(openApi, "/publicClass/plain"),
                "/publicClass/plain");
        assertEquals(
                runtime(PublicClassResource.class, "own"), buildTime(openApi, "/publicClass/own"), "/publicClass/own");
    }
}
