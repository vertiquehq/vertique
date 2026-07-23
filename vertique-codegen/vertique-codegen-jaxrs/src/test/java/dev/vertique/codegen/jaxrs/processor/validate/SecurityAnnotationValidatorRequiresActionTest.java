// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compile-time tests for the {@code @RequiresAction} conflict rule in
 * {@link SecurityAnnotationValidator}.
 *
 * <p>{@code @RequiresAction} AND-composes with {@code @RolesAllowed}/{@code @Authorized} but
 * conflicts with {@code @PermitAll} and {@code @DenyAll}. Each test compiles a fixture resource via
 * the full {@link JaxRsPipelineProcessor} and asserts the presence (or absence) of a compile-time
 * conflict diagnostic.
 */
class SecurityAnnotationValidatorRequiresActionTest {

    @Test
    @DisplayName("@RequiresAction + @PermitAll on same method → compilation error")
    void requiresActionPlusPermitAll_compilationError() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import dev.vertique.security.authz.RequiresAction;
                import jakarta.annotation.security.PermitAll;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/res")
                public class Res {
                    @GET @RequiresAction("cms.content.read") @PermitAll
                    public String get() { return ""; }
                }
                """));
        result.assertFailed();
        assertEquals(1, conflictErrorCount(result), "Expected exactly 1 @RequiresAction conflict error");
    }

    @Test
    @DisplayName("@RequiresAction + @DenyAll on same method → compilation error")
    void requiresActionPlusDenyAll_compilationError() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import dev.vertique.security.authz.RequiresAction;
                import jakarta.annotation.security.DenyAll;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/res")
                public class Res {
                    @GET @RequiresAction("cms.content.read") @DenyAll
                    public String get() { return ""; }
                }
                """));
        result.assertFailed();
        assertEquals(1, conflictErrorCount(result), "Expected exactly 1 @RequiresAction conflict error");
    }

    @Test
    @DisplayName("class-level @RequiresAction + @PermitAll → compilation error")
    void classLevelRequiresActionPlusPermitAll_compilationError() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import dev.vertique.security.authz.RequiresAction;
                import jakarta.annotation.security.PermitAll;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/res")
                @RequiresAction("cms.content.read")
                @PermitAll
                public class Res {
                    @GET
                    public String get() { return ""; }
                }
                """));
        result.assertFailed();
        assertEquals(1, conflictErrorCount(result), "Expected exactly 1 class-level @RequiresAction conflict error");
    }

    @Test
    @DisplayName("class-level @RequiresAction + @DenyAll → compilation error")
    void classLevelRequiresActionPlusDenyAll_compilationError() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import dev.vertique.security.authz.RequiresAction;
                import jakarta.annotation.security.DenyAll;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/res")
                @RequiresAction("cms.content.read")
                @DenyAll
                public class Res {
                    @GET
                    public String get() { return ""; }
                }
                """));
        result.assertFailed();
        assertEquals(1, conflictErrorCount(result), "Expected exactly 1 class-level @RequiresAction conflict error");
    }

    @Test
    @DisplayName("@RequiresAction + @RolesAllowed on same method → no error (valid AND-composition)")
    void requiresActionPlusRolesAllowed_noError() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import dev.vertique.security.authz.RequiresAction;
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/res")
                public class Res {
                    @GET @RequiresAction("cms.content.read") @RolesAllowed("admin")
                    public String get() { return ""; }
                }
                """));
        result.assertSuccess();
        assertEquals(0, conflictErrorCount(result), "Expected no @RequiresAction conflict error");
    }

    /**
     * Counts compile-time ERROR diagnostics that describe a {@code @RequiresAction} conflict.
     *
     * @param result the processor test result whose diagnostics are inspected
     * @return the number of {@code @RequiresAction} conflict error diagnostics
     */
    private int conflictErrorCount(ProcessorTestHarness.Result result) {
        return (int) result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .filter(d -> {
                    String msg = d.getMessage(null);
                    return msg != null && msg.contains("@RequiresAction");
                })
                .count();
    }
}
