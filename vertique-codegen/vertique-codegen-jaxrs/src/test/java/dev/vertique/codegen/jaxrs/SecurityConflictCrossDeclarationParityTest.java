// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parity proof for fail-closed cross-declaration security conflicts (issue #634 / OWASP A01 /
 * CWE-863): when security annotations disagree across declarations of the same method, both the
 * reflective runtime and the annotation processor must reject — nearer-declaration-wins must not
 * emit a served policy on the codegen path.
 *
 * <p>Covers both directions named by the issue: a class override and an interface default, plus
 * disagreeing {@code @RolesAllowed} values across two super-interfaces.
 */
class SecurityConflictCrossDeclarationParityTest {

    private static final String PKG = "dev.vertique.test.secconflict";

    // --- Reflective fixtures ---

    interface DenyApi {
        @GET
        @Path("/purge")
        @DenyAll
        String purge();
    }

    interface PermitDefaultApi extends DenyApi {
        @Override
        @PermitAll
        default String purge() {
            return "purged";
        }
    }

    @Path("/defaults")
    static class PermitDefaultResource implements PermitDefaultApi {}

    interface RestrictApi {
        @GET
        @Path("/purge")
        @DenyAll
        String purge();
    }

    @Path("/override")
    static class PermitOverrideResource implements RestrictApi {
        @Override
        @PermitAll
        public String purge() {
            return "purged";
        }
    }

    static class DenyBase {
        @GET
        @Path("/purge")
        @DenyAll
        public String purge() {
            return "denied";
        }
    }

    @Path("/subclass")
    static class PermitSubclassResource extends DenyBase {
        @Override
        @PermitAll
        public String purge() {
            return "purged";
        }
    }

    interface AdminRolesApi {
        @GET
        @Path("/item")
        @RolesAllowed("admin")
        String get();
    }

    interface UserRolesApi {
        @GET
        @Path("/item")
        @RolesAllowed("user")
        String get();
    }

    @Path("/roles")
    static class ConflictingRolesResource implements AdminRolesApi, UserRolesApi {
        @Override
        public String get() {
            return "item";
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("DenyAll on interface + PermitAll on overriding default: both engines reject")
    void denyAllPlusPermitAllOnDefault_rejectedByBoth() {
        assertThrows(SecurityPolicyViolationException.class, () -> new JaxRsRouteRegistrar()
                .scanResource(new PermitDefaultResource()));

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                src("DenyApi", """
                        public interface DenyApi {
                            @GET
                            @Path("/purge")
                            @DenyAll
                            String purge();
                        }
                        """),
                src("PermitDefaultApi", """
                        public interface PermitDefaultApi extends DenyApi {
                            @Override
                            @PermitAll
                            default String purge() {
                                return "purged";
                            }
                        }
                        """),
                src("PermitDefaultResource", """
                        @Path("/defaults")
                        public class PermitDefaultResource implements PermitDefaultApi {}
                        """));
        result.assertFailed();
        result.assertErrorMessage("Conflicting security annotations");
    }

    @Test
    @DisplayName("DenyAll on interface + PermitAll on class override: both engines reject")
    void denyAllPlusPermitAllOnClassOverride_rejectedByBoth() {
        assertThrows(SecurityPolicyViolationException.class, () -> new JaxRsRouteRegistrar()
                .scanResource(new PermitOverrideResource()));

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), src("RestrictApi", """
                        public interface RestrictApi {
                            @GET
                            @Path("/purge")
                            @DenyAll
                            String purge();
                        }
                        """), src("PermitOverrideResource", """
                        @Path("/override")
                        public class PermitOverrideResource implements RestrictApi {
                            @Override
                            @PermitAll
                            public String purge() {
                                return "purged";
                            }
                        }
                        """));
        result.assertFailed();
        result.assertErrorMessage("Conflicting security annotations");
    }

    @Test
    @DisplayName("DenyAll on superclass + PermitAll on subclass override: both engines reject")
    void denyAllPlusPermitAllOnSubclassOverride_rejectedByBoth() {
        assertThrows(SecurityPolicyViolationException.class, () -> new JaxRsRouteRegistrar()
                .scanResource(new PermitSubclassResource()));

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), src("DenyBase", """
                        public class DenyBase {
                            @GET
                            @Path("/purge")
                            @DenyAll
                            public String purge() {
                                return "denied";
                            }
                        }
                        """), src("PermitSubclassResource", """
                        @Path("/subclass")
                        public class PermitSubclassResource extends DenyBase {
                            @Override
                            @PermitAll
                            public String purge() {
                                return "purged";
                            }
                        }
                        """));
        result.assertFailed();
        result.assertErrorMessage("Conflicting security annotations");
    }

    @Test
    @DisplayName("different @RolesAllowed on two super-interfaces: both engines reject")
    void differentRolesAllowedOnTwoInterfaces_rejectedByBoth() {
        SecurityPolicyViolationException thrown =
                assertThrows(SecurityPolicyViolationException.class, () -> new JaxRsRouteRegistrar()
                        .scanResource(new ConflictingRolesResource()));
        assertTrue(
                thrown.getMessage().contains("Conflicting")
                        || thrown.getMessage().contains("conflict"),
                "runtime must surface a security conflict: " + thrown.getMessage());

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                src("AdminRolesApi", """
                        public interface AdminRolesApi {
                            @GET
                            @Path("/item")
                            @RolesAllowed("admin")
                            String get();
                        }
                        """),
                src("UserRolesApi", """
                        public interface UserRolesApi {
                            @GET
                            @Path("/item")
                            @RolesAllowed("user")
                            String get();
                        }
                        """),
                src("ConflictingRolesResource", """
                        @Path("/roles")
                        public class ConflictingRolesResource implements AdminRolesApi, UserRolesApi {
                            @Override
                            public String get() {
                                return "item";
                            }
                        }
                        """));
        result.assertFailed();
        result.assertErrorMessage("Conflicting security annotations");
    }

    private static JavaFileObject src(String simpleName, String body) {
        return SourceFiles.inline(PKG + "." + simpleName, "package " + PKG + ";\n\n" + """
                import jakarta.annotation.security.DenyAll;
                import jakarta.annotation.security.PermitAll;
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                """ + body);
    }
}
