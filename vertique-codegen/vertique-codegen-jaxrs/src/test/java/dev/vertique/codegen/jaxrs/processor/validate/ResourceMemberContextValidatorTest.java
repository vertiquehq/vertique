// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compile-time rejection of {@code @Context} on a resource field or constructor parameter, wired
 * through {@link JaxRsPipelineProcessor}. The runtime rejects the same declarations at startup.
 */
class ResourceMemberContextValidatorTest {

    @Test
    @DisplayName("@Context on a resource field is a compile error")
    void contextField_rejected() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import io.vertx.ext.web.RoutingContext;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.core.Context;

                @Path("/res")
                public class Res {
                    @Context RoutingContext routing;

                    @GET
                    public String get() { return ""; }
                }
                """));
        result.assertFailed();
        result.assertErrorMessage("@Context on field 'routing' of Res is never injected");
    }

    @Test
    @DisplayName("@Context on a resource constructor parameter is a compile error")
    void contextConstructorParameter_rejected() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import io.vertx.ext.web.RoutingContext;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.core.Context;

                @Path("/res")
                public class Res {
                    public Res(String name, @Context RoutingContext routing) {}

                    @GET
                    public String get() { return ""; }
                }
                """));
        result.assertFailed();
        result.assertErrorMessage("@Context on parameter 2 of a constructor of Res is never injected");
    }

    @Test
    @DisplayName("@Context on a superclass field is a compile error")
    void contextSuperclassField_rejected() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.Base", """
                        package dev.vertique.test;

                        import io.vertx.ext.web.RoutingContext;
                        import jakarta.ws.rs.core.Context;

                        public abstract class Base {
                            @Context protected RoutingContext routing;
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.Res", """
                        package dev.vertique.test;

                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;

                        @Path("/res")
                        public class Res extends Base {
                            @GET
                            public String get() { return ""; }
                        }
                        """));
        result.assertFailed();
        result.assertErrorMessage("@Context on field 'routing' of Base is never injected");
    }

    @Test
    @DisplayName("@Context on a method parameter still compiles")
    void contextMethodParameter_accepted() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import io.vertx.ext.web.RoutingContext;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.core.Context;

                @Path("/res")
                public class Res {
                    @GET
                    public String get(@Context RoutingContext routing) { return ""; }
                }
                """));
        result.assertSuccess();
    }

    @Test
    @DisplayName("a regex path variable named with an underscore is a compile error")
    void regexTemplateWithUnderscoreName_rejected() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                @Path("/u/{user_id:[0-9]+}")
                public class Res {
                    @GET
                    public String get(@PathParam("user_id") String userId) { return userId; }
                }
                """));
        result.assertFailed();
        result.assertErrorMessage("declares the variable 'user_id'");
    }

    @Test
    @DisplayName("an unconstrained variable named with an underscore beside a regex variable is a compile error")
    void unconstrainedUnderscoreNameInRegexTemplate_rejected() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                @Path("/u/{id:[0-9]+}/{sub_key}")
                public class Res {
                    @GET
                    public String get(@PathParam("id") String id, @PathParam("sub_key") String key) { return id; }
                }
                """));
        result.assertFailed();
        result.assertErrorMessage("declares the variable 'sub_key'");
    }

    @Test
    @DisplayName("an underscore name in a template with no regex constraint still compiles")
    void plainTemplateWithUnderscoreName_accepted() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                @Path("/u/{user_id}")
                public class Res {
                    @GET
                    public String get(@PathParam("user_id") String userId) { return userId; }
                }
                """));
        result.assertSuccess();
    }

    @Test
    @DisplayName("a variable name shared by the class and the method path is a compile error naming the method")
    void duplicateVariableAcrossClassAndMethod_rejected() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                @Path("/o/{id:\\\\d+}")
                public class Res {
                    @GET
                    @Path("/{id}")
                    public String get(@PathParam("id") String id) { return id; }
                }
                """));
        result.assertFailed();
        result.assertErrorMessage("Res.get()");
        result.assertErrorMessage("more than once");
        result.assertErrorMessage("'id'");
    }

    @Test
    @DisplayName("a variable name declared twice in a template with no regex constraint is a compile error")
    void duplicateVariableInPlainTemplate_rejected() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                @Path("/p/{id}")
                public class Res {
                    @GET
                    @Path("/x/{id}")
                    public String get(@PathParam("id") String id) { return id; }
                }
                """));
        result.assertFailed();
        result.assertErrorMessage("Res.get()");
        result.assertErrorMessage("more than once");
    }

    @Test
    @DisplayName("a regex constraint that does not compile is a compile error naming the method and the variable")
    void malformedRegexConstraint_rejected() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                package dev.vertique.test;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                @Path("/m")
                public class Res {
                    @GET
                    @Path("/{id:[0-9}")
                    public String get(@PathParam("id") String id) { return id; }
                }
                """));
        result.assertFailed();
        result.assertErrorMessage("Res.get()");
        result.assertErrorMessage("not a valid regular expression");
        result.assertErrorMessage("'id'");
    }

    @Test
    @DisplayName("two interfaces with different class-level paths are a compile error")
    void conflictingInterfacePaths_rejected() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.First", """
                        package dev.vertique.test;

                        @jakarta.ws.rs.Path("/first")
                        public interface First {}
                        """),
                SourceFiles.inline("dev.vertique.test.Second", """
                        package dev.vertique.test;

                        @jakarta.ws.rs.Path("/second")
                        public interface Second {}
                        """),
                SourceFiles.inline("dev.vertique.test.Res", """
                        package dev.vertique.test;

                        import jakarta.ws.rs.GET;

                        public class Res implements First, Second {
                            @GET
                            public String get() { return ""; }
                        }
                        """));
        result.assertFailed();
        result.assertErrorMessage("implements two interfaces with conflicting @Path declarations");
    }
}
