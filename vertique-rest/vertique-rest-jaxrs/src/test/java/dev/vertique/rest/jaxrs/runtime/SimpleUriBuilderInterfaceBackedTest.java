// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link SimpleUriBuilder} verifying that interface-declared {@code @Path}
 * annotations are resolved correctly via the merged annotation walk (MEDIUM-4 fix).
 *
 * <p>Prior to the fix, {@code path(Class)}, {@code path(Class, String)}, and
 * {@code path(Method)} all called {@code resource.getAnnotation(Path.class)} directly, which
 * returns {@code null} for a concrete implementation that carries no direct {@code @Path} but
 * implements an interface that does. After the fix they use
 * {@link dev.vertique.core.util.AnnotationResolver} so the interface-declared path is found.
 */
class SimpleUriBuilderInterfaceBackedTest {

    // --- Test fixtures ---

    /**
     * Interface declaring class-level and method-level {@code @Path}.
     */
    @Path("/iface-resources")
    interface ResourceApi {

        /**
         * Sub-path on the method.
         */
        @GET
        @Path("/{id}")
        String getById(String id);

        /**
         * Method with no sub-path.
         */
        @GET
        String list();
    }

    /**
     * Concrete implementation carrying no direct {@code @Path} annotations.
     */
    static class ConcreteResource implements ResourceApi {
        @Override
        public String getById(String id) {
            return id;
        }

        @Override
        public String list() {
            return "list";
        }
    }

    /**
     * Concrete implementation that carries its own {@code @Path} directly, overriding the
     * interface path.
     */
    @Path("/direct-resource")
    static class DirectResource implements ResourceApi {
        @Override
        public String getById(String id) {
            return id;
        }

        @Override
        public String list() {
            return "list";
        }
    }

    // --- Tests: path(Class) ---

    @Nested
    @DisplayName("path(Class) — resolves @Path from interface when class has no direct annotation")
    class PathClass {

        @Test
        @DisplayName("concrete class without direct @Path uses interface class-level @Path")
        void interfaceDeclaredClassPath() {
            URI uri = UriBuilder.fromPath("").path(ConcreteResource.class).build();
            assertEquals(
                    "/iface-resources",
                    uri.toString(),
                    "path(Class) must resolve interface-declared @Path('/iface-resources')");
        }

        @Test
        @DisplayName("concrete class with direct @Path uses its own @Path, not interface @Path")
        void directAnnotationWins() {
            URI uri = UriBuilder.fromPath("").path(DirectResource.class).build();
            assertEquals("/direct-resource", uri.toString(), "Direct class-level @Path must override interface @Path");
        }

        @Test
        @DisplayName("class with no @Path anywhere throws IllegalArgumentException")
        void noPathAnywhere_throws() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> UriBuilder.fromPath("").path(Object.class),
                    "Class with no @Path must throw IllegalArgumentException");
        }
    }

    // --- Tests: path(Class, String) ---

    @Nested
    @DisplayName("path(Class, String) — resolves method @Path from interface")
    class PathClassString {

        @Test
        @DisplayName("method without direct @Path uses interface method-level @Path — template check")
        void interfaceDeclaredMethodPath() {
            // Use toTemplate() to avoid requiring template substitution values
            String template = UriBuilder.fromPath("/base")
                    .path(ConcreteResource.class, "getById")
                    .toTemplate();
            assertEquals(
                    "/base/{id}",
                    template,
                    "path(Class, method) must resolve interface-declared method @Path('/{id}')");
        }

        @Test
        @DisplayName("method name with no @Path in hierarchy throws IllegalArgumentException")
        void noMethodPath_throws() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> UriBuilder.fromPath("/base").path(ConcreteResource.class, "list"),
                    "Method 'list' has no effective @Path — must throw IllegalArgumentException");
        }
    }

    // --- Tests: path(Method) ---

    @Nested
    @DisplayName("path(Method) — resolves @Path from interface method")
    class PathMethod {

        @Test
        @DisplayName("concrete method without direct @Path uses interface method-level @Path")
        void interfaceDeclaredMethodPath() throws Exception {
            java.lang.reflect.Method method = ConcreteResource.class.getMethod("getById", String.class);
            // Build with a value for the {id} template so build() does not throw
            URI uri = UriBuilder.fromPath("/base").path(method).build("42");
            assertEquals("/base/42", uri.toString(), "path(Method) must resolve interface-declared @Path('/{id}')");
        }

        @Test
        @DisplayName("concrete method without direct @Path produces template path before substitution")
        void interfaceDeclaredMethodPath_templatePreserved() throws Exception {
            java.lang.reflect.Method method = ConcreteResource.class.getMethod("getById", String.class);
            // buildFromEncoded() preserves template variables
            String template = UriBuilder.fromPath("/base").path(method).toTemplate();
            assertEquals("/base/{id}", template, "Template must reflect the interface-declared @Path('/{id}')");
        }

        @Test
        @DisplayName("concrete method with no @Path anywhere throws IllegalArgumentException")
        void noMethodPath_throws() throws Exception {
            java.lang.reflect.Method method = ConcreteResource.class.getMethod("list");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> UriBuilder.fromPath("/base").path(method),
                    "Method 'list' has no effective @Path — must throw IllegalArgumentException");
        }
    }

    // --- Combined class + method path ---

    @Nested
    @DisplayName("Combined class + method path from interface")
    class CombinedPath {

        @Test
        @DisplayName("path(Class) + path(Class, method) builds the full template from interface annotations")
        void fullPath_template_fromInterface() {
            String template = UriBuilder.fromPath("")
                    .path(ConcreteResource.class)
                    .path(ConcreteResource.class, "getById")
                    .toTemplate();
            assertEquals(
                    "/iface-resources/{id}",
                    template,
                    "Full template must be class-path + method-path from interface annotations");
        }
    }
}
