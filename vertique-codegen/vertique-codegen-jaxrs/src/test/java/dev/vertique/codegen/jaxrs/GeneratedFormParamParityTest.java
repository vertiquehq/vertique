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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that every declared FORM collection shape resolves to the SAME
 * {@link ResourceMethodMeta.ParamMeta} shape on the generated and the reflective dispatch paths
 * (legacy issue #155; plan finding F5, the §4 FORM invariant table, slice S5; review round 1
 * finding F11).
 *
 * <p>Before this branch the two paths disagreed: {@code ResourceScanner} rewrote every
 * collection-shaped {@code @FormParam}'s declared type to {@code List.class} while
 * {@code EffectiveJaxRsContractResolver} kept the declared type, so a declared
 * {@code @FormParam Set<String>} carried {@code type() == java.util.List} reflectively and
 * {@code type() == java.util.Set} generated. Both paths then failed at request time, and the
 * divergence was a latent {@code ClassCastException} hazard the moment extraction started returning
 * real collections — which is exactly what S5 does. This test pins the post-fix agreement across the
 * full declared FORM shape set: {@code List<String>}, {@code Set<String>}, {@code SortedSet<String>},
 * {@code NavigableSet<String>}, {@code Collection<String>}, and {@code String[]}.
 *
 * <p>The parity comparison uses two independently-declared representations of each declaration — one
 * plain compiled fixture scanned through the real, public
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

    // --- Reflective fixtures: plain compiled classes, no annotation processor involved ---

    /** Reflective representation of {@code @FormParam("tags") List<String>}. */
    @Path("/form-list-parity")
    @PermitAll
    static class FormListReflective {
        @POST
        public String handle(@FormParam("tags") List<String> tags) {
            return "";
        }
    }

    /** Reflective representation of {@code @FormParam("tags") Set<String>}. */
    @Path("/form-set-parity")
    @PermitAll
    static class FormSetReflective {
        @POST
        public String handle(@FormParam("tags") Set<String> tags) {
            return "";
        }
    }

    /** Reflective representation of {@code @FormParam("tags") SortedSet<String>}. */
    @Path("/form-sortedset-parity")
    @PermitAll
    static class FormSortedSetReflective {
        @POST
        public String handle(@FormParam("tags") SortedSet<String> tags) {
            return "";
        }
    }

    /** Reflective representation of {@code @FormParam("tags") NavigableSet<String>}. */
    @Path("/form-navigableset-parity")
    @PermitAll
    static class FormNavigableSetReflective {
        @POST
        public String handle(@FormParam("tags") NavigableSet<String> tags) {
            return "";
        }
    }

    /** Reflective representation of {@code @FormParam("tags") Collection<String>}. */
    @Path("/form-collection-parity")
    @PermitAll
    static class FormCollectionReflective {
        @POST
        public String handle(@FormParam("tags") Collection<String> tags) {
            return "";
        }
    }

    /** Reflective representation of {@code @FormParam("tags") String[]}. */
    @Path("/form-array-parity")
    @PermitAll
    static class FormArrayReflective {
        @POST
        public String handle(@FormParam("tags") String[] tags) {
            return "";
        }
    }

    /**
     * One matrix row: a declared FORM collection shape, represented once as a plain compiled
     * reflective fixture instance and once as APT source text.
     *
     * @param label                      human-readable row label for {@code @ParameterizedTest} names
     * @param reflectiveResourceInstance an instance of a plain compiled nested resource class
     * @param generatedSource            APT source text for an equivalent resource declaration
     * @param generatedResourceFqn       FQN of the resource class declared in {@code generatedSource}
     * @param expectedType               the declared shape's {@link Class} both paths must resolve
     *                                   for {@code type()} (e.g. {@code List.class},
     *                                   {@code String[].class})
     */
    private record FormShapeCase(
            String label,
            Object reflectiveResourceInstance,
            JavaFileObject generatedSource,
            String generatedResourceFqn,
            Class<?> expectedType) {}

    /**
     * Builds the matrix rows: every declared FORM collection shape the §4 contract names —
     * {@code List<String>}, {@code Set<String>}, {@code SortedSet<String>},
     * {@code NavigableSet<String>}, {@code Collection<String>}, and {@code String[]}.
     *
     * @return the matrix rows as JUnit 5 {@link Arguments}, named by their label
     */
    private static Stream<Arguments> formShapeCases() {
        List<FormShapeCase> cases = List.of(
                new FormShapeCase(
                        "List<String>",
                        new FormListReflective(),
                        SourceFiles.inline("dev.vertique.test.form.FormListGenerated", """
                                package dev.vertique.test.form;

                                import jakarta.ws.rs.FormParam;
                                import jakarta.ws.rs.POST;
                                import jakarta.ws.rs.Path;
                                import java.util.List;

                                @Path("/form-list-parity")
                                public class FormListGenerated {
                                    public FormListGenerated() {}

                                    @POST
                                    public String handle(@FormParam("tags") List<String> tags) { return ""; }
                                }
                                """),
                        "dev.vertique.test.form.FormListGenerated",
                        List.class),
                new FormShapeCase(
                        "Set<String>",
                        new FormSetReflective(),
                        SourceFiles.inline("dev.vertique.test.form.FormSetGenerated", """
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
                                """),
                        "dev.vertique.test.form.FormSetGenerated",
                        Set.class),
                new FormShapeCase(
                        "SortedSet<String>",
                        new FormSortedSetReflective(),
                        SourceFiles.inline("dev.vertique.test.form.FormSortedSetGenerated", """
                                package dev.vertique.test.form;

                                import jakarta.ws.rs.FormParam;
                                import jakarta.ws.rs.POST;
                                import jakarta.ws.rs.Path;
                                import java.util.SortedSet;

                                @Path("/form-sortedset-parity")
                                public class FormSortedSetGenerated {
                                    public FormSortedSetGenerated() {}

                                    @POST
                                    public String handle(@FormParam("tags") SortedSet<String> tags) { return ""; }
                                }
                                """),
                        "dev.vertique.test.form.FormSortedSetGenerated",
                        SortedSet.class),
                new FormShapeCase(
                        "NavigableSet<String>",
                        new FormNavigableSetReflective(),
                        SourceFiles.inline("dev.vertique.test.form.FormNavigableSetGenerated", """
                                package dev.vertique.test.form;

                                import jakarta.ws.rs.FormParam;
                                import jakarta.ws.rs.POST;
                                import jakarta.ws.rs.Path;
                                import java.util.NavigableSet;

                                @Path("/form-navigableset-parity")
                                public class FormNavigableSetGenerated {
                                    public FormNavigableSetGenerated() {}

                                    @POST
                                    public String handle(@FormParam("tags") NavigableSet<String> tags) { return ""; }
                                }
                                """),
                        "dev.vertique.test.form.FormNavigableSetGenerated",
                        NavigableSet.class),
                new FormShapeCase(
                        "Collection<String>",
                        new FormCollectionReflective(),
                        SourceFiles.inline("dev.vertique.test.form.FormCollectionGenerated", """
                                package dev.vertique.test.form;

                                import jakarta.ws.rs.FormParam;
                                import jakarta.ws.rs.POST;
                                import jakarta.ws.rs.Path;
                                import java.util.Collection;

                                @Path("/form-collection-parity")
                                public class FormCollectionGenerated {
                                    public FormCollectionGenerated() {}

                                    @POST
                                    public String handle(@FormParam("tags") Collection<String> tags) { return ""; }
                                }
                                """),
                        "dev.vertique.test.form.FormCollectionGenerated",
                        Collection.class),
                new FormShapeCase(
                        "String[]",
                        new FormArrayReflective(),
                        SourceFiles.inline("dev.vertique.test.form.FormArrayGenerated", """
                                package dev.vertique.test.form;

                                import jakarta.ws.rs.FormParam;
                                import jakarta.ws.rs.POST;
                                import jakarta.ws.rs.Path;

                                @Path("/form-array-parity")
                                public class FormArrayGenerated {
                                    public FormArrayGenerated() {}

                                    @POST
                                    public String handle(@FormParam("tags") String[] tags) { return ""; }
                                }
                                """),
                        "dev.vertique.test.form.FormArrayGenerated",
                        String[].class));
        return cases.stream().map(c -> Arguments.of(c.label(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formShapeCases")
    @DisplayName("declared FORM collection shape — generated and reflective agree on the ParamMeta shape (#155/F5)")
    void formParamCollectionShape_generatedAndReflective_agree(String label, FormShapeCase shapeCase) throws Exception {
        // Reflective side: the real, public entry point — exercises the real ResourceScanner FORM
        // branch (whose List.class normalization S5 removed).
        ResourceMethodMeta.ParamMeta reflective = new JaxRsRouteRegistrar()
                .scanResource(shapeCase.reflectiveResourceInstance())
                .get(0)
                .params()
                .get(0);

        // Oracle sanity check before comparing: the DECLARED shape must survive scanning.
        assertEquals(
                ResourceMethodMeta.ParamSource.FORM, reflective.source(), label + ": reflective source sanity check");
        assertEquals(
                shapeCase.expectedType(),
                reflective.type(),
                label + ": reflective type() sanity check — the declared shape, not List");
        assertEquals(String.class, reflective.componentType(), label + ": reflective componentType() sanity check");

        // Generated side: real annotation processor + real describe().
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), shapeCase.generatedSource());
        result.assertSuccess();

        List<ResourceMethodMeta> generatedMetas = callDescribe(
                result, shapeCase.generatedResourceFqn(), shapeCase.generatedResourceFqn() + "_JaxRsDescriptor");
        ResourceMethodMeta.ParamMeta generated = generatedMetas.get(0).params().get(0);

        // Parity — the assertions under test.
        assertEquals(reflective.source(), generated.source(), label + ": parity breach on param source");
        assertEquals(reflective.type(), generated.type(), label + ": parity breach on the declared collection type()");
        assertEquals(
                reflective.componentType(), generated.componentType(), label + ": parity breach on componentType()");

        // genericType is contractually BODY-only, so FORM must stay null on BOTH paths (§4 invariant
        // table): the FORM collection path needs only type() + componentType() to materialize.
        assertNull(reflective.genericType(), label + ": reflective genericType() must stay null for FORM");
        assertNull(generated.genericType(), label + ": generated genericType() must stay null for FORM");
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
