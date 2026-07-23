// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for closed issue #29: {@link ResourceMethodInvoker#invokeMethod} historically
 * called {@code Method.invoke(...)} without {@link java.lang.reflect.Method#setAccessible(boolean)
 * setAccessible(true)}, so non-public resource methods discovered via {@code getDeclaredMethods()}
 * threw {@code IllegalAccessException} at request time.
 *
 * <p>Fix: {@link ResourceScanner#scanResource(Object, List)} now calls {@code setAccessible(true)}
 * once per discovered method. This test asserts that contract for protected, package-private, and
 * private resource methods.
 */
class ResourceScannerAccessibilityTest {

    @Path("/mixed")
    static class MixedAccessResource {

        @GET
        @Path("/public")
        public Future<String> publicMethod() {
            return Future.succeededFuture("public");
        }

        @POST
        @Path("/protected")
        protected Future<String> protectedMethod() {
            return Future.succeededFuture("protected");
        }

        @PUT
        @Path("/private")
        private Future<String> privateMethod() {
            return Future.succeededFuture("private");
        }
    }

    @Test
    @DisplayName("scanResource calls setAccessible(true) on all discovered Method objects regardless of visibility")
    void allDiscoveredMethodsAreAccessible() {
        ResourceScanner scanner = new ResourceScanner(new SecurityPolicyBuilder());
        List<ResourceMethodMeta> result = scanner.scanResource(new MixedAccessResource());

        assertEquals(3, result.size(), "Expected three discovered methods (public, protected, private)");
        for (ResourceMethodMeta meta : result) {
            assertTrue(
                    meta.method().canAccess(meta.resourceInstance()),
                    "Method " + meta.method().getName()
                            + " should be accessible after ResourceScanner.scanResource (closed #29)");
        }
    }
}
