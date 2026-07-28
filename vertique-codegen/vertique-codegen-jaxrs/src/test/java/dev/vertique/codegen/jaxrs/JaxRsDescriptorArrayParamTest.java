// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED tests for array-typed parameter FQN resolution in generated JAX-RS descriptors (legacy
 * issue #153 / plan finding F1, S1).
 *
 * <p>{@code JaxRsDescriptorEmitter} emits a parameter's erased type as a string FQN constant and
 * resolves it inside the generated {@code describe()} body via
 * {@link GeneratedJaxRsDescriptorSupport#resolveClass(String, ClassLoader)}. For array-typed
 * parameters, {@code TypeMirrorFqn.erasedFqn} falls through to {@code TypeMirror.toString()},
 * which yields Java <em>source</em> form (e.g. {@code "java.lang.String[]"}). {@code resolveClass}
 * passes that string straight to {@code Class.forName}, which cannot load source-array form —
 * the generated helper wraps the resulting {@link ClassNotFoundException} in an
 * {@link IllegalStateException} and rethrows it, so {@code describe()} throws.
 *
 * <p>Each test compiles a small resource fixture through {@link JaxRsPipelineProcessor}, then
 * invokes the generated descriptor's {@code describe(...)} method reflectively (mirroring
 * {@code JaxRsDescriptorEmitterTest}'s {@code callDescribe} helper) — asserting only that
 * compilation succeeds would NOT reproduce this defect, since the FQN string is resolved lazily
 * inside {@code describe()}, not at annotation-processing time.
 *
 * <p>These tests assert the desired SUCCESS behavior (no throw, correct resolved type) and are
 * therefore expected to FAIL until the fix lands.
 */
class JaxRsDescriptorArrayParamTest {

    // --- Helper: load and call descriptor (mirrors JaxRsDescriptorEmitterTest#callDescribe) ---

    /**
     * Loads the generated {@code _JaxRsDescriptor} class, instantiates it, and calls
     * {@code describe(resourceInstance, support, emptyList)} to obtain the method meta list.
     *
     * @param result        the compilation result
     * @param resourceFqn   FQN of the generated resource class (compiled from fixture)
     * @param descriptorFqn FQN of the expected generated descriptor class
     * @return the list of {@link ResourceMethodMeta} returned by {@code describe()}
     * @throws Exception if any reflection step fails (including a wrapped {@link ClassNotFoundException}
     *                    surfaced as {@code IllegalStateException} by the generated code)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ResourceMethodMeta> callDescribe(
            ProcessorTestHarness.Result result, String resourceFqn, String descriptorFqn) throws Exception {
        ClassLoader cl = result.generatedClassLoader();
        Class<?> resourceClass = cl.loadClass(resourceFqn);
        Object resourceInstance = resourceClass.getDeclaredConstructor().newInstance();

        Class<?> descriptorClass = result.loadGeneratedClass(descriptorFqn);
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        GeneratedJaxRsDescriptorSupport support = new GeneratedJaxRsDescriptorSupport();

        Method describeMethod =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        return (List<ResourceMethodMeta>)
                describeMethod.invoke(descriptor, resourceInstance, support, Collections.emptyList());
    }

    // --- F1: single-dimensional array query param ---

    @Test
    @DisplayName("@QueryParam String[] — describe() resolves the array type without throwing")
    void arrayQueryParam_describe_resolvesArrayType() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ArrayQueryResource", """
                        package dev.vertique.test;

                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/array-query")
                        public class ArrayQueryResource {
                            public ArrayQueryResource() {}

                            @GET
                            public String list(@QueryParam("tags") String[] tags) { return ""; }
                        }
                        """));

        result.assertSuccess();

        List<ResourceMethodMeta> metas = assertDoesNotThrow(
                () -> callDescribe(
                        result,
                        "dev.vertique.test.ArrayQueryResource",
                        "dev.vertique.test.ArrayQueryResource_JaxRsDescriptor"),
                "describe() must not throw for an array-typed @QueryParam (F1)");

        ResourceMethodMeta.ParamMeta paramMeta = metas.get(0).params().get(0);
        assertEquals(String[].class, paramMeta.type(), "Array-typed @QueryParam must resolve to String[].class");
    }

    // --- F2: byte[] body remains a documented working shape under codegen ---

    @Test
    @DisplayName("unannotated byte[] body param — describe() resolves byte[].class without throwing")
    void primitiveArrayBody_describe_resolvesByteArray() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ByteBodyResource", """
                        package dev.vertique.test;

                        import jakarta.ws.rs.Consumes;
                        import jakarta.ws.rs.POST;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.core.MediaType;

                        @Path("/byte-body")
                        public class ByteBodyResource {
                            public ByteBodyResource() {}

                            @POST
                            @Consumes(MediaType.APPLICATION_OCTET_STREAM)
                            public String upload(byte[] body) { return "ok"; }
                        }
                        """));

        result.assertSuccess();

        List<ResourceMethodMeta> metas = assertDoesNotThrow(
                () -> callDescribe(
                        result,
                        "dev.vertique.test.ByteBodyResource",
                        "dev.vertique.test.ByteBodyResource_JaxRsDescriptor"),
                "describe() must not throw for an unannotated byte[] body param (F2) — codegen must not "
                        + "regress this documented reflective-path working shape");

        ResourceMethodMeta.ParamMeta paramMeta = metas.get(0).params().get(0);
        assertEquals(byte[].class, paramMeta.type(), "byte[] body param must resolve to byte[].class");
    }

    // --- Multi-dimensional array param ---

    @Test
    @DisplayName("String[][] param — describe() resolves the two-dimensional array type without throwing")
    void multiDimensionalArrayParam_describe_resolves() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.MatrixResource", """
                        package dev.vertique.test;

                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/matrix")
                        public class MatrixResource {
                            public MatrixResource() {}

                            @GET
                            public String list(@QueryParam("matrix") String[][] matrix) { return ""; }
                        }
                        """));

        result.assertSuccess();

        List<ResourceMethodMeta> metas = assertDoesNotThrow(
                () -> callDescribe(
                        result, "dev.vertique.test.MatrixResource", "dev.vertique.test.MatrixResource_JaxRsDescriptor"),
                "describe() must not throw for a two-dimensional array param — dimension stripping must "
                        + "handle more than one trailing [] pair");

        ResourceMethodMeta.ParamMeta paramMeta = metas.get(0).params().get(0);
        assertEquals(String[][].class, paramMeta.type(), "String[][] param must resolve to String[][].class");
    }

    // --- F4: nested (inner) element type array param ---

    @Test
    @DisplayName("nested-type element array param (Outer.Inner[]) — describe() resolves without throwing (decides F4)")
    void nestedTypeArrayParam_describe_resolves() throws Exception {
        // This test is the decision procedure for plan finding F4: TypeMirrorFqn.erasedFqn's array
        // branch falls back to TypeMirror.toString(), which is expected to yield the DOTTED source
        // form "dev.vertique.test.Outer.Inner[]" for a nested element type rather than the BINARY
        // form "dev.vertique.test.Outer$Inner[]" that Class.forName (after array-dimension
        // stripping) requires. If this test is still red after S1's fix alone, S2 (binary base
        // names in TypeMirrorFqn's array branch) is required; if it goes green from S1 alone, S2
        // is skipped and that must be recorded in the plan's Amendments section, never silently.
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Outer", """
                        package dev.vertique.test;

                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/nested")
                        public class Outer {
                            public Outer() {}

                            public enum Inner { A, B }

                            @GET
                            public String list(@QueryParam("v") Outer.Inner[] v) { return ""; }
                        }
                        """));

        result.assertSuccess();

        assertDoesNotThrow(
                () -> callDescribe(result, "dev.vertique.test.Outer", "dev.vertique.test.Outer_JaxRsDescriptor"),
                "describe() must not throw for a nested-element-type array param (F4) — the emitted array "
                        + "FQN's base name must resolve to the nested type, whether that requires binary "
                        + "Outer$Inner form or is already handled by dimension-stripping alone");
    }
}
