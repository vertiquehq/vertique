// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.RouteEntriesProbe;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins route-order parity between the two JAX-RS engines. Route order is decided at run time by one
 * sort over {@link ResourceMethodMeta#path()} and the operation id; no build-time route table exists.
 * Both engines therefore agree on the order exactly when they report equal HTTP methods, path strings
 * and operation ids, so this test compares those triples for the paths that decide the ranking: a
 * catch-all beside a literal tail, a mixed {@code {name}.txt} segment, a regex variable beside a plain
 * one, and a constraint with an alternation.
 *
 * <p>The fixture is one resource, represented twice, following
 * {@link GeneratedDescriptorAnnotationParityTest}: as a nested static type scanned by
 * {@link JaxRsRouteRegistrar#scanResource(Object)} and as a public top-level source compiled once
 * through {@link JaxRsPipelineProcessor}, whose generated {@code describe()} is called directly.
 *
 * <p>The registered route sequence is compared through {@link RouteEntriesProbe}, which reads the
 * route entries {@code JaxRsRouteRegistrar} creates its Vert.x routes from, in order, including the
 * extra route of each more specific alternative.
 */
class RouteOrderParityTest {

    private static final String PKG = "dev.vertique.test.routeorder";

    private static final String RESOURCE_FQN = PKG + ".RankedResource";

    /** Each method name with the path the fixture declares for it. */
    private static final List<String> EXPECTED = List.of(
            "alternation|/ro/{p:a|b/admin}",
            "catchAll|/ro/c/{p:.+}",
            "catchAllSecret|/ro/c/{id}/secret",
            "mixedAdmin|/ro/f/~admin.txt",
            "mixedOpen|/ro/f/{name}.txt",
            "profile|/ro/u/{name}/profile",
            "regexAction|/ro/u/{id:\\d+}/{action}");

    private static List<ResourceMethodMeta> reflectiveMetas;

    private static List<ResourceMethodMeta> generatedMetas;

    @Path("/ro")
    static class RankedResource {
        public RankedResource() {}

        @GET
        @Path("/c/{p:.+}")
        public String catchAll(@PathParam("p") String p) {
            return "";
        }

        @GET
        @Path("/c/{id}/secret")
        public String catchAllSecret(@PathParam("id") String id) {
            return "";
        }

        @GET
        @Path("/f/{name}.txt")
        public String mixedOpen(@PathParam("name") String name) {
            return "";
        }

        @GET
        @Path("/f/~admin.txt")
        public String mixedAdmin() {
            return "";
        }

        @GET
        @Path("/{p:a|b/admin}")
        public String alternation(@PathParam("p") String p) {
            return "";
        }

        @GET
        @Path("/u/{name}/profile")
        public String profile(@PathParam("name") String name) {
            return "";
        }

        @GET
        @Path("/u/{id:\\d+}/{action}")
        public String regexAction(@PathParam("id") String id, @PathParam("action") String action) {
            return "";
        }
    }

    private static javax.tools.JavaFileObject rankedResourceSource() {
        return SourceFiles.inline(RESOURCE_FQN, "package " + PKG + ";\n\n" + """
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                @Path("/ro")
                public class RankedResource {
                    public RankedResource() {}

                    @GET
                    @Path("/c/{p:.+}")
                    public String catchAll(@PathParam("p") String p) {
                        return "";
                    }

                    @GET
                    @Path("/c/{id}/secret")
                    public String catchAllSecret(@PathParam("id") String id) {
                        return "";
                    }

                    @GET
                    @Path("/f/{name}.txt")
                    public String mixedOpen(@PathParam("name") String name) {
                        return "";
                    }

                    @GET
                    @Path("/f/~admin.txt")
                    public String mixedAdmin() {
                        return "";
                    }

                    @GET
                    @Path("/{p:a|b/admin}")
                    public String alternation(@PathParam("p") String p) {
                        return "";
                    }

                    @GET
                    @Path("/u/{name}/profile")
                    public String profile(@PathParam("name") String name) {
                        return "";
                    }

                    @GET
                    @Path("/u/{id:\\\\d+}/{action}")
                    public String regexAction(@PathParam("id") String id, @PathParam("action") String action) {
                        return "";
                    }
                }
                """);
    }

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void describeTheFixtureWithBothEngines() throws Exception {
        reflectiveMetas = new JaxRsRouteRegistrar().scanResource(new RankedResource());
        for (ResourceMethodMeta meta : reflectiveMetas) {
            assertNull(meta.executionPlan(), "the nested fixture must take the reflective path: " + meta);
        }

        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), rankedResourceSource());
        result.assertSuccess();
        Object instance = result.generatedClassLoader()
                .loadClass(RESOURCE_FQN)
                .getDeclaredConstructor()
                .newInstance();
        Class<?> descriptorClass = result.loadGeneratedClass(RESOURCE_FQN + "_JaxRsDescriptor");
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method describe =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        generatedMetas = (List<ResourceMethodMeta>)
                describe.invoke(descriptor, instance, new GeneratedJaxRsDescriptorSupport(), new ArrayList<>());
    }

    /** One line per meta, sorted by method name so neither engine's method order matters. */
    private static List<String> routeKeys(List<ResourceMethodMeta> metas) {
        return metas.stream()
                .sorted(Comparator.comparing(meta -> meta.method().getName()))
                .map(meta -> meta.method().getName() + "|" + meta.path())
                .toList();
    }

    private static List<String> operations(List<ResourceMethodMeta> metas) {
        return metas.stream()
                .sorted(Comparator.comparing(meta -> meta.method().getName()))
                .map(meta -> meta.httpMethod() + " " + meta.operationId())
                .toList();
    }

    @Test
    @DisplayName("both engines report the declared path strings the route sort reads")
    void bothEnginesReportTheDeclaredPaths() {
        assertFalse(reflectiveMetas.isEmpty());
        assertEquals(EXPECTED, routeKeys(reflectiveMetas), "reflective engine paths");
        assertEquals(EXPECTED, routeKeys(generatedMetas), "generated engine paths");
    }

    @Test
    @DisplayName("both engines report equal HTTP methods and operation ids, the sort's last tie-break")
    void bothEnginesReportEqualOperationIds() {
        assertFalse(operations(reflectiveMetas).isEmpty());
        assertEquals(operations(reflectiveMetas), operations(generatedMetas));
    }

    @Test
    @DisplayName("both engines register the same routes in the same order")
    void bothEnginesRegisterTheSameRouteOrder() {
        List<String> reflective = RouteEntriesProbe.registrationOrder(reflectiveMetas);
        List<String> generated = RouteEntriesProbe.registrationOrder(generatedMetas);
        assertEquals(reflective, generated);
        assertEquals(
                List.of(
                        "/ro/f/~admin.txt",
                        "/ro/u/{name}/profile",
                        "/ro/c/{id}/secret",
                        "/ro/{p:b/admin}",
                        "/ro/f/{name}.txt",
                        "/ro/u/{id:\\d+}/{action}",
                        "/ro/c/{p:.+}",
                        "/ro/{p:a|b/admin}"),
                reflective.stream().map(line -> line.split(" ")[1]).toList(),
                "registration order of the reflective engine");
    }
}
