// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies {@link StubDescriptors}, this module's synthetic-descriptor test double: the neutral
 * defaults every consumer relies on, per-axis round-tripping, defensive copying performed in the
 * setter rather than in {@code build()}, independence of descriptors built from one reused builder,
 * and null rejection for both a null argument and a list carrying a null element.
 */
class StubDescriptorsTest {

    private static final BodyDescriptor BODY = new BodyDescriptor(String.class, null, List.of());

    private static final ParamDescriptor PARAM =
            new ParamDescriptor("id", ParamLocation.QUERY, String.class, null, null, null, List.of());

    private static final FilePartDescriptor FILE_PART = new FilePartDescriptor("avatar", List.of("image/png"), 1024L);

    // --- Defaults and round-trip ---

    @Test
    @DisplayName("A builder with no setter applied yields the documented neutral descriptor on all 13 members")
    void defaultsAreNeutral() {
        JaxRsOperationDescriptor descriptor = StubDescriptors.builder().build();

        assertEquals("op", descriptor.operationId());
        assertEquals("GET", descriptor.httpMethod());
        assertEquals("/", descriptor.routeTemplate());
        assertEquals(List.of(), descriptor.consumes());
        assertEquals(List.of(), descriptor.produces());
        assertEquals(new SecurityPolicy.None(), descriptor.securityPolicy());
        assertEquals(List.of(), descriptor.securityRequirementSets());
        assertEquals(List.of(), descriptor.methodAnnotations());
        assertEquals(List.of(), descriptor.classAnnotations());
        assertEquals(Optional.empty(), descriptor.findAnnotation(Deprecated.class));
        assertEquals(List.of(), descriptor.parameters());
        assertEquals(List.of(), descriptor.fileParts());
        assertEquals(Optional.empty(), descriptor.body());
    }

    @Test
    @DisplayName("Every setter round-trips its own value onto the built descriptor")
    void eachAxisRoundTrips() {
        JaxRsOperationDescriptor descriptor = StubDescriptors.builder()
                .operationId("createThing")
                .httpMethod("POST")
                .routeTemplate("/things/{id}")
                .consumes(List.of("application/json"))
                .parameters(List.of(PARAM))
                .fileParts(List.of(FILE_PART))
                .body(BODY)
                .build();

        assertEquals("createThing", descriptor.operationId());
        assertEquals("POST", descriptor.httpMethod());
        assertEquals("/things/{id}", descriptor.routeTemplate());
        assertEquals(List.of("application/json"), descriptor.consumes());
        assertEquals(List.of(PARAM), descriptor.parameters());
        assertEquals(List.of(FILE_PART), descriptor.fileParts());
        assertEquals(Optional.of(BODY), descriptor.body());
    }

    // --- Immutability ---

    @ParameterizedTest(name = "{0}, mutatedBeforeBuild={1}")
    @MethodSource("listAxesByMutationOrder")
    @DisplayName("A list setter copies its argument, so later mutation of that argument reaches no descriptor")
    void listSettersCopyDefensively(ListAxis axis, boolean mutateBeforeBuild) {
        List<Object> mutable = new ArrayList<>();
        mutable.add(axis.sample());

        StubDescriptors.Builder builder = StubDescriptors.builder();
        axis.setter().accept(builder, mutable);

        JaxRsOperationDescriptor descriptor;
        if (mutateBeforeBuild) {
            // The distinguishing case: a copy taken inside build() would still observe this mutation,
            // so only a setter-side copy keeps the built descriptor at one element.
            mutable.add(axis.sample());
            descriptor = builder.build();
        } else {
            descriptor = builder.build();
            mutable.add(axis.sample());
        }

        assertEquals(
                List.of(axis.sample()),
                axis.reader().apply(descriptor),
                axis.name() + " must not observe a mutation of the caller's list");
    }

    @Test
    @DisplayName("Reusing a builder for a second build leaves the first descriptor untouched")
    void builderReuseDoesNotDisturbEarlierDescriptors() {
        StubDescriptors.Builder builder = StubDescriptors.builder()
                .operationId("first")
                .httpMethod("POST")
                .routeTemplate("/first")
                .consumes(List.of("application/json"))
                .parameters(List.of(PARAM))
                .fileParts(List.of(FILE_PART))
                .body(BODY);
        JaxRsOperationDescriptor first = builder.build();

        BodyDescriptor otherBody = new BodyDescriptor(Integer.class, null, List.of());
        JaxRsOperationDescriptor second = builder.operationId("second")
                .httpMethod("PUT")
                .routeTemplate("/second")
                .consumes(List.of("text/plain"))
                .parameters(List.of())
                .fileParts(List.of())
                .body(otherBody)
                .build();

        assertEquals("first", first.operationId());
        assertEquals("POST", first.httpMethod());
        assertEquals("/first", first.routeTemplate());
        assertEquals(List.of("application/json"), first.consumes());
        assertEquals(List.of(PARAM), first.parameters());
        assertEquals(List.of(FILE_PART), first.fileParts());
        assertEquals(Optional.of(BODY), first.body());

        assertEquals("second", second.operationId());
        assertEquals("PUT", second.httpMethod());
        assertEquals("/second", second.routeTemplate());
        assertEquals(List.of("text/plain"), second.consumes());
        assertEquals(List.of(), second.parameters());
        assertEquals(List.of(), second.fileParts());
        assertEquals(Optional.of(otherBody), second.body());
    }

    // --- Null rejection ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullRejections")
    @DisplayName("Setters reject a null argument, and list setters also reject a list containing a null element")
    void nullsAreRejected(String label, Executable call) {
        assertThrows(NullPointerException.class, call, label + " must be rejected");
    }

    // --- MethodSource providers ---

    /**
     * One list-valued builder axis: a sample element, how to set the axis on a builder, and how to
     * read the corresponding member back off a built descriptor.
     *
     * @param name   the axis name, used as the parameterized display name
     * @param sample a sample element of the axis's element type
     * @param setter applies an erased element list to the builder's setter for this axis
     * @param reader reads this axis's member off a built descriptor
     */
    private record ListAxis(
            String name,
            Object sample,
            BiConsumer<StubDescriptors.Builder, List<Object>> setter,
            Function<JaxRsOperationDescriptor, List<?>> reader) {

        @Override
        public String toString() {
            return name;
        }
    }

    @SuppressWarnings("unchecked")
    private static Stream<Arguments> listAxesByMutationOrder() {
        Stream<ListAxis> axes = Stream.of(
                new ListAxis(
                        "consumes",
                        "application/json",
                        (builder, values) -> builder.consumes((List<String>) (List<?>) values),
                        JaxRsOperationDescriptor::consumes),
                new ListAxis(
                        "parameters",
                        PARAM,
                        (builder, values) -> builder.parameters((List<ParamDescriptor>) (List<?>) values),
                        JaxRsOperationDescriptor::parameters),
                new ListAxis(
                        "fileParts",
                        FILE_PART,
                        (builder, values) -> builder.fileParts((List<FilePartDescriptor>) (List<?>) values),
                        JaxRsOperationDescriptor::fileParts));
        return axes.flatMap(axis -> Stream.of(arguments(axis, true), arguments(axis, false)));
    }

    private static Stream<Arguments> nullRejections() {
        List<String> consumesWithNull = new ArrayList<>();
        consumesWithNull.add(null);
        List<ParamDescriptor> parametersWithNull = new ArrayList<>();
        parametersWithNull.add(null);
        List<FilePartDescriptor> filePartsWithNull = new ArrayList<>();
        filePartsWithNull.add(null);

        return Stream.of(
                arguments("operationId(null)", (Executable)
                        () -> StubDescriptors.builder().operationId(null)),
                arguments("httpMethod(null)", (Executable)
                        () -> StubDescriptors.builder().httpMethod(null)),
                arguments("routeTemplate(null)", (Executable)
                        () -> StubDescriptors.builder().routeTemplate(null)),
                arguments("consumes(null)", (Executable)
                        () -> StubDescriptors.builder().consumes(null)),
                arguments("parameters(null)", (Executable)
                        () -> StubDescriptors.builder().parameters(null)),
                arguments("fileParts(null)", (Executable)
                        () -> StubDescriptors.builder().fileParts(null)),
                arguments("body(null)", (Executable)
                        () -> StubDescriptors.builder().body(null)),
                arguments("consumes(list with a null element)", (Executable)
                        () -> StubDescriptors.builder().consumes(consumesWithNull)),
                arguments("parameters(list with a null element)", (Executable)
                        () -> StubDescriptors.builder().parameters(parametersWithNull)),
                arguments("fileParts(list with a null element)", (Executable)
                        () -> StubDescriptors.builder().fileParts(filePartsWithNull)));
    }
}
