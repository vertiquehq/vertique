// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.security.authz.Authorized;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parity proof for per-axis cross-declaration security merging (issue #634): {@code @RolesAllowed}
 * and {@code @Authorized} are independent axes, so roles on one declaration and scopes on another
 * combine into one AND-{@code Constrained} policy on both the reflective runtime and the annotation
 * processor. Same-axis disagreement and {@code @DenyAll}/{@code @PermitAll} mixed with constrained
 * kinds stay fail-closed on both engines.
 */
class SecurityAxisMergeParityTest {

    private static final String PKG = "dev.vertique.test.secaxis";

    // --- Reflective fixtures: method level ---

    interface RolesApi {
        @GET
        @Path("/m1")
        @RolesAllowed("admin")
        String get();
    }

    @Path("/m1")
    static class ScopesOverrideResource implements RolesApi {
        @Override
        @Authorized(scopes = {"read", "write"})
        public String get() {
            return "";
        }
    }

    interface ScopesApi {
        @GET
        @Path("/m2")
        @Authorized(
                scopes = {"read"},
                matchAll = false)
        String get();
    }

    @Path("/m2")
    static class RolesOverrideResource implements ScopesApi {
        @Override
        @RolesAllowed("admin")
        public String get() {
            return "";
        }
    }

    // --- Reflective fixtures: class level ---

    @RolesAllowed("admin")
    interface RolesClassApi {
        @GET
        @Path("/c1")
        String get();
    }

    @Path("/c1")
    @Authorized(scopes = {"read"})
    static class ScopesClassResource implements RolesClassApi {
        @Override
        public String get() {
            return "";
        }
    }

    @Authorized(scopes = {"read", "write"})
    interface ScopesClassApi {
        @GET
        @Path("/c2")
        String get();
    }

    @Path("/c2")
    @RolesAllowed({"admin", "ops"})
    static class RolesClassResource implements ScopesClassApi {
        @Override
        public String get() {
            return "";
        }
    }

    // --- Reflective fixtures: negative ---

    interface AuthorizedReadApi {
        @GET
        @Path("/n1")
        @Authorized(scopes = {"read"})
        String get();
    }

    @Path("/n1")
    static class AuthorizedWriteResource implements AuthorizedReadApi {
        @Override
        @Authorized(scopes = {"write"})
        public String get() {
            return "";
        }
    }

    interface DenyAllApi {
        @GET
        @Path("/n2")
        @DenyAll
        String get();
    }

    @Path("/n2")
    static class AuthorizedOverDenyResource implements DenyAllApi {
        @Override
        @Authorized(scopes = {"read"})
        public String get() {
            return "";
        }
    }

    interface PermitAllApi {
        @GET
        @Path("/n3")
        @PermitAll
        String get();
    }

    @Path("/n3")
    static class RolesOverPermitResource implements PermitAllApi {
        @Override
        @RolesAllowed("admin")
        public String get() {
            return "";
        }
    }

    @RolesAllowed("admin")
    interface RolesClassConflictApi {
        @GET
        @Path("/n4")
        String get();
    }

    @Path("/n4")
    @PermitAll
    static class PermitClassResource implements RolesClassConflictApi {
        @Override
        public String get() {
            return "";
        }
    }

    // --- Positive parity ---

    @Test
    @DisplayName("method: @RolesAllowed on interface + @Authorized(scopes) on override merges into Constrained")
    void rolesOnInterfaceScopesOnOverride_mergedByBoth() throws Exception {
        SecurityPolicy policy = assertGeneratedEqualsReflective(
                new ScopesOverrideResource(),
                "ScopesOverrideResource",
                src("RolesApi", """
                        public interface RolesApi {
                            @GET
                            @Path("/m1")
                            @RolesAllowed("admin")
                            String get();
                        }
                        """),
                src("ScopesOverrideResource", """
                        @Path("/m1")
                        public class ScopesOverrideResource implements RolesApi {
                            @Override
                            @Authorized(scopes = {"read", "write"})
                            public String get() { return ""; }
                        }
                        """));
        assertEquals(new SecurityPolicy.Constrained(List.of("admin"), List.of("read", "write"), true), policy);
    }

    @Test
    @DisplayName("method: @Authorized(scopes) on interface + @RolesAllowed on override merges into Constrained")
    void scopesOnInterfaceRolesOnOverride_mergedByBoth() throws Exception {
        SecurityPolicy policy = assertGeneratedEqualsReflective(
                new RolesOverrideResource(),
                "RolesOverrideResource",
                src("ScopesApi", """
                        public interface ScopesApi {
                            @GET
                            @Path("/m2")
                            @Authorized(scopes = {"read"}, matchAll = false)
                            String get();
                        }
                        """),
                src("RolesOverrideResource", """
                        @Path("/m2")
                        public class RolesOverrideResource implements ScopesApi {
                            @Override
                            @RolesAllowed("admin")
                            public String get() { return ""; }
                        }
                        """));
        assertEquals(new SecurityPolicy.Constrained(List.of("admin"), List.of("read"), false), policy);
    }

    @Test
    @DisplayName("class level: @RolesAllowed on interface + @Authorized(scopes) on class merges into Constrained")
    void rolesOnInterfaceScopesOnClass_mergedByBoth() throws Exception {
        SecurityPolicy policy = assertGeneratedEqualsReflective(
                new ScopesClassResource(),
                "ScopesClassResource",
                src("RolesClassApi", """
                        @RolesAllowed("admin")
                        public interface RolesClassApi {
                            @GET
                            @Path("/c1")
                            String get();
                        }
                        """),
                src("ScopesClassResource", """
                        @Path("/c1")
                        @Authorized(scopes = {"read"})
                        public class ScopesClassResource implements RolesClassApi {
                            @Override
                            public String get() { return ""; }
                        }
                        """));
        assertEquals(new SecurityPolicy.Constrained(List.of("admin"), List.of("read"), true), policy);
    }

    @Test
    @DisplayName("class level: @Authorized(scopes) on interface + @RolesAllowed on class merges into Constrained")
    void scopesOnInterfaceRolesOnClass_mergedByBoth() throws Exception {
        SecurityPolicy policy = assertGeneratedEqualsReflective(
                new RolesClassResource(),
                "RolesClassResource",
                src("ScopesClassApi", """
                        @Authorized(scopes = {"read", "write"})
                        public interface ScopesClassApi {
                            @GET
                            @Path("/c2")
                            String get();
                        }
                        """),
                src("RolesClassResource", """
                        @Path("/c2")
                        @RolesAllowed({"admin", "ops"})
                        public class RolesClassResource implements ScopesClassApi {
                            @Override
                            public String get() { return ""; }
                        }
                        """));
        assertEquals(new SecurityPolicy.Constrained(List.of("admin", "ops"), List.of("read", "write"), true), policy);
    }

    // --- Negative parity ---

    @Test
    @DisplayName("different @Authorized scopes across declarations: both engines reject")
    void differentAuthorizedScopes_rejectedByBoth() {
        assertRejectedByBoth(
                new AuthorizedWriteResource(), src("AuthorizedReadApi", """
                        public interface AuthorizedReadApi {
                            @GET
                            @Path("/n1")
                            @Authorized(scopes = {"read"})
                            String get();
                        }
                        """), src("AuthorizedWriteResource", """
                        @Path("/n1")
                        public class AuthorizedWriteResource implements AuthorizedReadApi {
                            @Override
                            @Authorized(scopes = {"write"})
                            public String get() { return ""; }
                        }
                        """));
    }

    @Test
    @DisplayName("@DenyAll on interface + @Authorized on override: both engines reject")
    void denyAllPlusAuthorized_rejectedByBoth() {
        assertRejectedByBoth(
                new AuthorizedOverDenyResource(), src("DenyAllApi", """
                        public interface DenyAllApi {
                            @GET
                            @Path("/n2")
                            @DenyAll
                            String get();
                        }
                        """), src("AuthorizedOverDenyResource", """
                        @Path("/n2")
                        public class AuthorizedOverDenyResource implements DenyAllApi {
                            @Override
                            @Authorized(scopes = {"read"})
                            public String get() { return ""; }
                        }
                        """));
    }

    @Test
    @DisplayName("@PermitAll on interface + @RolesAllowed on override: both engines reject")
    void permitAllPlusRolesAllowed_rejectedByBoth() {
        assertRejectedByBoth(
                new RolesOverPermitResource(), src("PermitAllApi", """
                        public interface PermitAllApi {
                            @GET
                            @Path("/n3")
                            @PermitAll
                            String get();
                        }
                        """), src("RolesOverPermitResource", """
                        @Path("/n3")
                        public class RolesOverPermitResource implements PermitAllApi {
                            @Override
                            @RolesAllowed("admin")
                            public String get() { return ""; }
                        }
                        """));
    }

    @Test
    @DisplayName("class level: @RolesAllowed on interface + @PermitAll on class: both engines reject")
    void classLevelRolesPlusPermitAll_rejectedByBoth() {
        assertRejectedByBoth(
                new PermitClassResource(), src("RolesClassConflictApi", """
                        @RolesAllowed("admin")
                        public interface RolesClassConflictApi {
                            @GET
                            @Path("/n4")
                            String get();
                        }
                        """), src("PermitClassResource", """
                        @Path("/n4")
                        @PermitAll
                        public class PermitClassResource implements RolesClassConflictApi {
                            @Override
                            public String get() { return ""; }
                        }
                        """));
    }

    // --- Helpers ---

    private static void assertRejectedByBoth(Object reflectiveResource, JavaFileObject... sources) {
        assertThrows(SecurityPolicyViolationException.class, () -> new JaxRsRouteRegistrar()
                .scanResource(reflectiveResource));

        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), sources);
        result.assertFailed();
        result.assertErrorMessage("Conflicting security annotations");
    }

    @SuppressWarnings("unchecked")
    private static SecurityPolicy assertGeneratedEqualsReflective(
            Object reflectiveResource, String resourceSimpleName, JavaFileObject... sources) throws Exception {
        ResourceMethodMeta reflective =
                new JaxRsRouteRegistrar().scanResource(reflectiveResource).get(0);
        assertNull(reflective.executionPlan(), "the nested fixture must take the reflective path");

        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), sources);
        result.assertSuccess();

        String fqn = PKG + "." + resourceSimpleName;
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
        return generated.get(0).securityPolicy();
    }

    private static JavaFileObject src(String simpleName, String body) {
        return SourceFiles.inline(PKG + "." + simpleName, "package " + PKG + ";\n\n" + """
                import dev.vertique.security.authz.Authorized;
                import jakarta.annotation.security.DenyAll;
                import jakarta.annotation.security.PermitAll;
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                """ + body);
    }
}
