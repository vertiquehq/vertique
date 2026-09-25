// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.security.Authorized;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins that the generated descriptor builds the same {@link SecurityPolicy} value the reflective
 * {@code AnnotationSecurityPolicyResolver} builds, including {@code requireAllScopes}.
 *
 * <p>The runtime sets {@code requireAllScopes} only when {@code @Authorized} declares scopes;
 * with no scopes the flag is {@code false}. The enforcer ignores the flag when there are no
 * scopes, so the value is inert there, but the generated {@code ResourceMethodMeta} must still
 * equal the scanner's.
 */
class GeneratedSecurityPolicyParityTest {

    private static final String PKG = "dev.vertique.test.secparity";

    @Path("/roles")
    static class RolesOnly {
        @GET
        @RolesAllowed("admin")
        public String get() {
            return "";
        }
    }

    @Path("/roles-authorized")
    static class RolesAndScopelessAuthorized {
        @GET
        @RolesAllowed("admin")
        @Authorized
        public String get() {
            return "";
        }
    }

    @Path("/scopes")
    static class ScopesDefaultMatch {
        @GET
        @Authorized(scopes = {"read", "write"})
        public String get() {
            return "";
        }
    }

    @Path("/scopes-any")
    static class ScopesMatchAny {
        @GET
        @Authorized(
                scopes = {"read", "write"},
                matchAll = false)
        public String get() {
            return "";
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(new RolesOnly(), "RolesOnly", """
                        @Path("/roles")
                        public class RolesOnly {
                            @GET
                            @RolesAllowed("admin")
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new RolesAndScopelessAuthorized(), "RolesAndScopelessAuthorized", """
                        @Path("/roles-authorized")
                        public class RolesAndScopelessAuthorized {
                            @GET
                            @RolesAllowed("admin")
                            @Authorized
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new ScopesDefaultMatch(), "ScopesDefaultMatch", """
                        @Path("/scopes")
                        public class ScopesDefaultMatch {
                            @GET
                            @Authorized(scopes = {"read", "write"})
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new ScopesMatchAny(), "ScopesMatchAny", """
                        @Path("/scopes-any")
                        public class ScopesMatchAny {
                            @GET
                            @Authorized(scopes = {"read", "write"}, matchAll = false)
                            public String get() { return ""; }
                        }
                        """));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("cases")
    @DisplayName("generated SecurityPolicy equals the reflective one")
    @SuppressWarnings("unchecked")
    void generatedPolicyEqualsReflective(Object reflectiveResource, String simpleName, String body) throws Exception {
        ResourceMethodMeta reflective =
                new JaxRsRouteRegistrar().scanResource(reflectiveResource).get(0);
        assertNull(reflective.executionPlan(), "the nested fixture must take the reflective path");

        String fqn = PKG + "." + simpleName;
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline(fqn, """
                package %s;

                import dev.vertique.rest.core.security.Authorized;
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                %s""".formatted(PKG, body)));
        result.assertSuccess();
        Object instance = result.generatedClassLoader()
                .loadClass(fqn)
                .getDeclaredConstructor()
                .newInstance();
        Class<?> descriptorClass = result.loadGeneratedClass(fqn + "_JaxRsDescriptor");
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        List<ResourceMethodMeta> generated = (List<ResourceMethodMeta>) descriptorClass
                .getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class)
                .invoke(descriptor, instance, new GeneratedJaxRsDescriptorSupport(), new ArrayList<>());

        assertEquals(1, generated.size());
        assertEquals(reflective.securityPolicy(), generated.get(0).securityPolicy());
    }
}
