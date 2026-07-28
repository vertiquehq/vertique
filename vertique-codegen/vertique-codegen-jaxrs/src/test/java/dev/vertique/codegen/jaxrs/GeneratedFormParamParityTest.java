// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a collection-shaped {@code @FormParam} resolves to the SAME
 * {@link ResourceMethodMeta.ParamMeta} shape on the generated and the reflective dispatch paths
 * (legacy issue #155; plan finding F5, the §4 FORM invariant table, slice S5).
 *
 * <p>Before this branch the two paths disagreed: {@code ResourceScanner} rewrote every
 * collection-shaped {@code @FormParam}'s declared type to {@code List.class} while
 * {@code EffectiveJaxRsContractResolver} kept the declared type, so a declared
 * {@code @FormParam Set<String>} carried {@code type() == java.util.List} reflectively and
 * {@code type() == java.util.Set} generated. Both paths then failed at request time, and the
 * divergence was a latent {@code ClassCastException} hazard the moment extraction started returning
 * real collections — which is exactly what S5 does. This test pins the post-fix agreement.
 *
 * <p>The parity comparison uses two independently-declared representations of the same declaration —
 * one plain compiled fixture scanned through the real, public
 * {@link JaxRsRouteRegistrar#scanResource(Object)} entry point, and one source-text fixture compiled
 * through the real {@link JaxRsPipelineProcessor} — mirroring
 * {@link GeneratedArrayParamParityTest}'s convention. A single shared compiled class cannot serve
 * both sides: any resource compiled via {@link ProcessorTestHarness} carries a real
 * {@code _JaxRsDescriptor} companion in its own classloader, so
 * {@code GeneratedJaxRsDescriptorRegistry}'s classloader-keyed lookup would short-circuit
 * {@code scanResource} onto the generated fast path and defeat the comparison.
 *
 * <p>This proof cannot live in {@code vertique-rest-jaxrs}: the annotation processor that produces
 * the generated side is in this module, and {@code vertique-rest-jaxrs} cannot depend on it.
 */
class GeneratedFormParamParityTest {

    // --- Reflective fixture: a plain compiled class, no annotation processor involved ---

    /** Reflective representation of {@code @FormParam("tags") Set<String>}. */
    @Path("/form-set-parity")
    @PermitAll
    static class FormSetReflective {
        @POST
        public String handle(@FormParam("tags") Set<String> tags) {
            return "";
        }
    }

    @Test
    @DisplayName("@FormParam Set<String> — generated and reflective agree on the ParamMeta shape (#155/F5)")
    void formParamSet_generatedAndReflective_agree() throws Exception {
        // Reflective side: the real, public entry point — exercises the real ResourceScanner FORM
        // branch (whose List.class normalization S5 removed).
        ResourceMethodMeta.ParamMeta reflective = new JaxRsRouteRegistrar()
                .scanResource(new FormSetReflective())
                .get(0)
                .params()
                .get(0);

        // Oracle sanity check before comparing: the DECLARED collection type must survive scanning.
        assertEquals(ResourceMethodMeta.ParamSource.FORM, reflective.source(), "reflective source sanity check");
        assertEquals(Set.class, reflective.type(), "reflective type() sanity check — the declared Set, not List");
        assertEquals(String.class, reflective.componentType(), "reflective componentType() sanity check");

        // Generated side: real annotation processor + real describe().
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.form.FormSetGenerated", """
                        package dev.vertique.test.form;

                        import jakarta.ws.rs.FormParam;
                        import jakarta.ws.rs.POST;
                        import jakarta.ws.rs.Path;
                        import java.util.Set;

                        @Path("/form-set-parity")
                        public class FormSetGenerated {
                            public FormSetGenerated() {}

                            @POST
                            public String handle(@FormParam("tags") Set<String> tags) { return ""; }
                        }
                        """));
        result.assertSuccess();

        List<ResourceMethodMeta> generatedMetas = callDescribe(
                result,
                "dev.vertique.test.form.FormSetGenerated",
                "dev.vertique.test.form.FormSetGenerated_JaxRsDescriptor");
        ResourceMethodMeta.ParamMeta generated = generatedMetas.get(0).params().get(0);

        // Parity — the assertions under test.
        assertEquals(reflective.source(), generated.source(), "parity breach on param source");
        assertEquals(reflective.type(), generated.type(), "parity breach on the declared collection type()");
        assertEquals(reflective.componentType(), generated.componentType(), "parity breach on componentType()");

        // genericType is contractually BODY-only, so FORM must stay null on BOTH paths (§4 invariant
        // table): the FORM collection path needs only type() + componentType() to materialize.
        assertNull(reflective.genericType(), "reflective genericType() must stay null for FORM");
        assertNull(generated.genericType(), "generated genericType() must stay null for FORM");
    }

    // --- Helper: load and call describe() (mirrors GeneratedArrayParamParityTest#callDescribe) ---

    /**
     * Loads the generated {@code _JaxRsDescriptor} class, instantiates it, and calls
     * {@code describe(resourceInstance, support, emptyList)} to obtain the method meta list.
     *
     * @param result        the compilation result
     * @param resourceFqn   FQN of the generated resource class (compiled from the source fixture)
     * @param descriptorFqn FQN of the expected generated descriptor class
     * @return the list of {@link ResourceMethodMeta} returned by {@code describe()}
     * @throws Exception if any reflection step fails
     */
    @SuppressWarnings("unchecked")
    private static List<ResourceMethodMeta> callDescribe(
            ProcessorTestHarness.Result result, String resourceFqn, String descriptorFqn) throws Exception {
        ClassLoader cl = result.generatedClassLoader();
        Class<?> resourceClass = cl.loadClass(resourceFqn);
        Object resourceInstance = resourceClass.getDeclaredConstructor().newInstance();

        Class<?> descriptorClass = result.loadGeneratedClass(descriptorFqn);
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        GeneratedJaxRsDescriptorSupport support = new GeneratedJaxRsDescriptorSupport();

        java.lang.reflect.Method describeMethod =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        return (List<ResourceMethodMeta>)
                describeMethod.invoke(descriptor, resourceInstance, support, Collections.emptyList());
    }
}
