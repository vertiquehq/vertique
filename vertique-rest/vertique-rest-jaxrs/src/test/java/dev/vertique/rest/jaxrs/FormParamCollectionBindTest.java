// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.FormFieldEntityPart;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.EntityPart;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for multi-value {@code @FormParam} collection binding, frozen in
 * {@code docs/plans/feat-param-shape-parity.md} §4 decisions 2–6 (slice S5). Mirrors
 * {@link SetAndArrayQueryParamBindTest}'s and {@link CollectionParamStateMachineTest}'s style: most
 * tests build a {@link ResourceMethodMeta.ParamMeta} directly so they exercise
 * {@link ParameterExtractor}'s FORM extraction against the <em>declared</em> collection type.
 *
 * <p>What these tests exist to prevent regressing — the behavior before ADR-0191 (plan §2 F5, F6,
 * F7, F13). Each bullet describes the OLD defect, not current behavior:
 * <ul>
 *   <li>{@code ResourceScanner.java:440-454} rewrote every collection-shaped {@code @FormParam}'s
 *       declared type to {@code List.class}, so a declared {@code Set<String>} carried
 *       {@code ParamMeta.type() == List.class}.</li>
 *   <li>{@code ParameterExtractor.extractFormParam} had native branches only for
 *       {@code FileUpload}/{@code EntityPart}/{@code List<FileUpload>}/{@code List<EntityPart>};
 *       every other FORM collection fell to a single-value {@code getFormAttribute(name)} +
 *       {@code coerceString}, which asked for a converter targeting the collection type — there is
 *       none, so it threw {@code ParamConverterNotFoundException} (mapped to HTTP 500).</li>
 *   <li>{@code @DefaultValue} on an absent FORM collection hit the same 500, for the same reason.</li>
 *   <li>FORM collection elements never traversed the input-policy chain (the FORM scalar branch
 *       already did, at {@code ParameterExtractor.java:789-791}).</li>
 *   <li>The two native FORM list branches ({@code Collectors.toList()},
 *       {@code new ArrayList<>()}) and {@code extractAllEntityParts}'s aggregate were mutable; only
 *       the {@code FILE_UPLOADS} aggregate ({@code List.copyOf}) was already read-only.</li>
 *   <li>A non-{@code List} native multipart shape (e.g. {@code Set<FileUpload>}) was not rejected
 *       at startup: {@code RouteValidator.isSupportedFileUploadTarget} only ran when {@code @FilePart}
 *       was declared, and the {@code List.class} normalization masked the real declared shape
 *       from every other check, so it slid through registration silently.</li>
 * </ul>
 */
class FormParamCollectionBindTest {

    // --- Fixture resources for extractor-level tests (manual ParamMeta) ---

    /** Resource exposing one method per FORM collection/native-multipart shape under test. */
    static final class FormResource {
        @SuppressWarnings("unused")
        public String list(List<String> tags) {
            return String.valueOf(tags);
        }

        @SuppressWarnings("unused")
        public String set(Set<Integer> tags) {
            return String.valueOf(tags);
        }

        @SuppressWarnings("unused")
        public String sortedSet(SortedSet<Integer> ids) {
            return String.valueOf(ids);
        }

        @SuppressWarnings("unused")
        public String array(Integer[] ids) {
            return String.valueOf(ids.length);
        }

        @SuppressWarnings("unused")
        public String collection(Collection<String> tags) {
            return String.valueOf(tags);
        }

        @SuppressWarnings("unused")
        public String integer(Integer id) {
            return String.valueOf(id);
        }

        @SuppressWarnings("unused")
        public String integerList(List<Integer> ids) {
            return String.valueOf(ids);
        }

        @SuppressWarnings("unused")
        public String fileUpload(FileUpload upload) {
            return "unused";
        }

        @SuppressWarnings("unused")
        public String entityPart(EntityPart part) {
            return "unused";
        }

        @SuppressWarnings("unused")
        public String fileUploadList(List<FileUpload> uploads) {
            return "unused";
        }

        @SuppressWarnings("unused")
        public String entityPartList(List<EntityPart> parts) {
            return "unused";
        }
    }

    /** Resource scanned for real by {@link JaxRsRouteRegistrar} to prove ParamMeta invariants. */
    @Path("/scanned-form")
    static class ScannedFormResource {

        @POST
        @Operation(operationId = "scannedForm")
        public String tags(@FormParam("tags") Set<String> tags) {
            return "unused";
        }
    }

    /** Resource declaring a non-{@code List} native multipart collection shape of {@code FileUpload}. */
    @Path("/native-multipart-set")
    static class NativeMultipartSetResource {

        @POST
        @Operation(operationId = "nativeMultipartSet")
        public String post(@FormParam("uploads") Set<FileUpload> uploads) {
            return "unused";
        }
    }

    /**
     * Resource declaring a non-{@code List} native multipart collection shape of {@code EntityPart}.
     *
     * <p>The {@code EntityPart} twin of {@link NativeMultipartSetResource}. Without it,
     * {@code RouteValidator.isNativelyMaterializedFormCollection}'s {@code EntityPart} arm is only
     * ever evaluated to {@code true} (by {@code RouteStartupValidationTest}'s
     * {@code List<EntityPart>} fixture), leaving the arm that actually raises the violation unproven.
     */
    @Path("/native-multipart-entity-part-set")
    static class NativeMultipartEntityPartSetResource {

        @POST
        @Operation(operationId = "nativeMultipartEntityPartSet")
        public String post(@FormParam("parts") Set<EntityPart> parts) {
            return "unused";
        }
    }

    // --- Test doubles for the input-policy-chain test ---

    /** Marker {@link Canonicalizer} used only to make a route chain non-empty; never invoked directly. */
    static final class MarkerCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /**
     * {@link InputObjectProcessor} stub that upper-cases every {@link String} it is handed, so a call
     * observably proves whether processing happened (unlike a Mockito identity stub).
     */
    static final class UppercasingProcessor implements InputObjectProcessor {
        @Override
        public Object processInput(
                Object intermediateBody,
                Type targetType,
                EffectiveInputPolicies policies,
                InputLocation location,
                InputFieldNameResolver nameResolver) {
            if (intermediateBody instanceof String s) {
                return s.toUpperCase(Locale.ROOT);
            }
            return intermediateBody;
        }

        @Override
        public void precomputeFieldNameResolution(Type declaredType, InputFieldNameResolver resolver) {
            // This double resolves no per-type metadata, so there is nothing to precompute.
        }
    }

    /**
     * {@link InputObjectProcessor} stub standing in for a real canonicalizer that <em>normalizes</em> a
     * value rather than merely rewriting it: it strips surrounding whitespace, so {@code " 5"} becomes
     * {@code "5"}. Whether it runs before or after conversion is therefore observable — a numeric
     * element only converts when the chain ran <em>first</em>.
     */
    static final class TrimmingProcessor implements InputObjectProcessor {
        @Override
        public Object processInput(
                Object intermediateBody,
                Type targetType,
                EffectiveInputPolicies policies,
                InputLocation location,
                InputFieldNameResolver nameResolver) {
            if (intermediateBody instanceof String s) {
                return s.strip();
            }
            return intermediateBody;
        }

        @Override
        public void precomputeFieldNameResolution(Type declaredType, InputFieldNameResolver resolver) {
            // This double resolves no per-type metadata, so there is nothing to precompute.
        }
    }

    // --- Fixture builders ---

    /**
     * Builds a FORM {@link ResourceMethodMeta.ParamMeta} with no {@code @DefaultValue}.
     *
     * @param name          the parameter name
     * @param type          the declared parameter type (collection interface or array)
     * @param componentType the element type, or {@code null} for a native scalar target
     * @return the built {@link ResourceMethodMeta.ParamMeta}
     */
    private static ResourceMethodMeta.ParamMeta formParam(String name, Class<?> type, Class<?> componentType) {
        return formParam(name, type, componentType, null);
    }

    /**
     * Builds a FORM {@link ResourceMethodMeta.ParamMeta}. {@code genericType} is always {@code null}:
     * per §4's frozen contract, {@code genericType} stays BODY-only and FORM never populates it.
     *
     * @param name          the parameter name
     * @param type          the declared parameter type (collection interface or array)
     * @param componentType the element type, or {@code null} for a native scalar target
     * @param defaultValue  the {@code @DefaultValue} string, or {@code null}
     * @return the built {@link ResourceMethodMeta.ParamMeta}
     */
    private static ResourceMethodMeta.ParamMeta formParam(
            String name, Class<?> type, Class<?> componentType, String defaultValue) {
        return new ResourceMethodMeta.ParamMeta(
                name, ResourceMethodMeta.ParamSource.FORM, type, componentType, null, defaultValue, (Annotation[])
                        null);
    }

    /**
     * Builds the unnamed aggregate {@link ResourceMethodMeta.ParamMeta} for the {@code FILE_UPLOADS} /
     * {@code ENTITY_PARTS} sources, matching {@code ResourceScanner}'s own aggregate shape
     * (declared type always {@code List.class}, no name, no default).
     *
     * @param source        {@code FILE_UPLOADS} or {@code ENTITY_PARTS}
     * @param componentType {@code FileUpload.class} or {@code EntityPart.class}
     * @return the built aggregate {@link ResourceMethodMeta.ParamMeta}
     */
    private static ResourceMethodMeta.ParamMeta aggregateParam(
            ResourceMethodMeta.ParamSource source, Class<?> componentType) {
        return new ResourceMethodMeta.ParamMeta(
                null, source, List.class, componentType, null, null, (Annotation[]) null);
    }

    /**
     * Builds a {@link ResourceMethodMeta} for {@code method} with the given ordered params and empty
     * route-level input-policy chains.
     *
     * @param method the reflected resource method
     * @param params the ordered parameter metadata
     * @return the built {@link ResourceMethodMeta}
     */
    private static ResourceMethodMeta metaFor(Method method, List<ResourceMethodMeta.ParamMeta> params) {
        return metaFor(method, params, List.of(), List.of());
    }

    /**
     * Builds a {@link ResourceMethodMeta} for {@code method} with the given ordered params and
     * explicit route-level input-policy chains.
     *
     * @param method     the reflected resource method
     * @param params     the ordered parameter metadata
     * @param routeCanon the route-level canonicalizer chain
     * @param routeSanit the route-level sanitizer chain
     * @return the built {@link ResourceMethodMeta}
     */
    private static ResourceMethodMeta metaFor(
            Method method,
            List<ResourceMethodMeta.ParamMeta> params,
            List<Class<? extends Canonicalizer>> routeCanon,
            List<Class<? extends Sanitizer>> routeSanit) {
        return new ResourceMethodMeta(
                new FormResource(),
                method,
                method.getName(),
                "POST",
                "/things",
                params,
                String.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                routeCanon,
                routeSanit);
    }

    private static ParameterExtractor extractorFor(ResourceMethodMeta meta) {
        return new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()));
    }

    private static ParameterExtractor extractorFor(ResourceMethodMeta meta, InputObjectProcessor processor) {
        return new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()), processor);
    }

    /**
     * Looks up the first parameter's metadata for {@code methodName} on {@code resource} via a real
     * {@link JaxRsRouteRegistrar} scan, so ParamMeta-invariant tests exercise the actual scanning
     * pipeline rather than a hand-built fixture.
     *
     * @param resource   the resource instance to scan
     * @param methodName the resource method name to find
     * @return the first parameter's metadata
     */
    private static ResourceMethodMeta.ParamMeta firstParamOf(Object resource, String methodName) {
        for (ResourceMethodMeta meta : new JaxRsRouteRegistrar().scanResource(resource)) {
            if (meta.method().getName().equals(methodName)) {
                return meta.params().get(0);
            }
        }
        throw new AssertionError("method not found: " + methodName);
    }

    /**
     * Builds a {@link BoundRequest} stub with empty path/query/header/cookie maps and a null body.
     * FORM extraction never reads {@code BoundRequest} maps, so this fixture is shared by every test.
     *
     * @return the built {@link BoundRequest} stub
     */
    private static BoundRequest emptyBoundRequest() {
        return new BoundRequest() {
            @Override
            public Map<String, RequestValue> pathParameters() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> query() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> headers() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> cookies() {
                return Map.of();
            }

            @Override
            public RequestValue body() {
                return RequestValue.of(null);
            }

            @Override
            public HttpServerRequest raw() {
                return null;
            }
        };
    }

    /**
     * Builds a mocked {@link RoutingContext} exposing {@code formValues} as Vert.x form attributes
     * (each name may carry multiple values, matching a repeated {@code x-www-form-urlencoded} field)
     * and {@code fileUploads} as the multipart file-upload list.
     *
     * @param formValues  the form attribute values, keyed by field name
     * @param fileUploads the multipart file uploads
     * @return the built {@link RoutingContext} mock
     */
    private static RoutingContext formRoutingContext(
            Map<String, List<String>> formValues, List<FileUpload> fileUploads) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.fileUploads()).thenReturn(fileUploads);
        when(ctx.vertx()).thenReturn(mock(Vertx.class));

        MultiMap attrs = MultiMap.caseInsensitiveMultiMap();
        formValues.forEach((name, values) -> values.forEach(v -> attrs.add(name, v)));
        when(request.formAttributes()).thenReturn(attrs);
        // Matches real HttpServerRequest semantics: getFormAttribute(name) returns the FIRST value.
        when(request.getFormAttribute(any())).thenAnswer(inv -> attrs.get(inv.getArgument(0, String.class)));
        return ctx;
    }

    /**
     * Builds a mocked {@link FileUpload} reporting {@code name} for {@link FileUpload#name()}.
     *
     * @param name the reported file-upload name
     * @return the built {@link FileUpload} mock
     */
    private static FileUpload fileUpload(String name) {
        FileUpload fu = mock(FileUpload.class);
        when(fu.name()).thenReturn(name);
        return fu;
    }

    // --- 1-5. Every collection/array shape binds all submitted values ---

    @Test
    @DisplayName("@FormParam List<String> binds all submitted values (today: 500 ParamConverterNotFoundException)")
    void formParamList_bindsAllValues() throws Exception {
        Method method = FormResource.class.getMethod("list", List.class);
        ResourceMethodMeta.ParamMeta param = formParam("a", List.class, String.class);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        RoutingContext ctx = formRoutingContext(Map.of("a", List.of("1", "2")), List.of());
        Object[] args = extractor.extractArguments(ctx, emptyBoundRequest());

        List<?> list = assertInstanceOf(List.class, args[0]);
        assertEquals(2, list.size());
        assertTrue(list.contains("1"));
        assertTrue(list.contains("2"));
    }

    @Test
    @DisplayName("@FormParam Set<Integer> binds all submitted values into a Set (today: 500)")
    void formParamSet_bindsAllValuesAsSet() throws Exception {
        Method method = FormResource.class.getMethod("set", Set.class);
        ResourceMethodMeta.ParamMeta param = formParam("a", Set.class, Integer.class);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        RoutingContext ctx = formRoutingContext(Map.of("a", List.of("1", "2")), List.of());
        Object[] args = extractor.extractArguments(ctx, emptyBoundRequest());

        Set<?> set = assertInstanceOf(Set.class, args[0]);
        assertEquals(2, set.size());
        assertTrue(set.contains(1));
        assertTrue(set.contains(2));
    }

    @Test
    @DisplayName("@FormParam SortedSet<Integer> binds all submitted values, sorted (today: 500)")
    void formParamSortedSet_bindsSorted() throws Exception {
        Method method = FormResource.class.getMethod("sortedSet", SortedSet.class);
        ResourceMethodMeta.ParamMeta param = formParam("a", SortedSet.class, Integer.class);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        RoutingContext ctx = formRoutingContext(Map.of("a", List.of("3", "1", "2")), List.of());
        Object[] args = extractor.extractArguments(ctx, emptyBoundRequest());

        SortedSet<?> set = assertInstanceOf(SortedSet.class, args[0]);
        assertEquals(List.of(1, 2, 3), List.copyOf(set), "a SortedSet must hold sorted, coerced Integers");
    }

    @Test
    @DisplayName("@FormParam Integer[] binds all submitted values into an array (today: 500)")
    void formParamArray_bindsArray() throws Exception {
        Method method = FormResource.class.getMethod("array", Integer[].class);
        ResourceMethodMeta.ParamMeta param = formParam("a", Integer[].class, Integer.class);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        RoutingContext ctx = formRoutingContext(Map.of("a", List.of("1", "2")), List.of());
        Object[] args = extractor.extractArguments(ctx, emptyBoundRequest());

        Integer[] array = assertInstanceOf(Integer[].class, args[0]);
        assertEquals(2, array.length);
        assertTrue(List.of(array).containsAll(List.of(1, 2)));
    }

    @Test
    @DisplayName("@FormParam Collection<String> binds all submitted values into a List (today: 500)")
    void formParamCollection_bindsList() throws Exception {
        Method method = FormResource.class.getMethod("collection", Collection.class);
        ResourceMethodMeta.ParamMeta param = formParam("a", Collection.class, String.class);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        RoutingContext ctx = formRoutingContext(Map.of("a", List.of("x", "y")), List.of());
        Object[] args = extractor.extractArguments(ctx, emptyBoundRequest());

        List<?> list = assertInstanceOf(List.class, args[0]);
        assertEquals(2, list.size());
        assertTrue(list.containsAll(List.of("x", "y")));
    }

    // --- 6. Declared type preserved in ParamMeta (real scan, not a manual fixture) ---

    @Test
    @DisplayName("ResourceScanner preserves the declared Set<String> type for @FormParam (today: List.class)")
    void formParamCollection_declaredTypePreservedInParamMeta() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new ScannedFormResource(), "tags");

        assertEquals(Set.class, pm.type(), "the declared collection type must be preserved, not normalized to List");
        assertEquals(String.class, pm.componentType());
        assertNull(pm.genericType(), "genericType is contractually BODY-only and must stay null for FORM");
    }

    // --- 7. Absence with @DefaultValue on a collection ---

    @Test
    @DisplayName(
            "Absent @FormParam List<String> with @DefaultValue(\"x\") yields a single-entry collection (today: 500)")
    void formParamCollection_absentWithDefault_yieldsSingleEntry() throws Exception {
        Method method = FormResource.class.getMethod("list", List.class);
        ResourceMethodMeta.ParamMeta param = formParam("a", List.class, String.class, "x");
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        // formAttributes().getAll("a") on an absent field returns an EMPTY LIST, not null. A naive
        // green implementation that materializes immediately from getAll() before checking presence
        // would silently drop the @DefaultValue and return an empty collection instead of this
        // single-entry one.
        RoutingContext ctx = formRoutingContext(Map.of(), List.of());
        Object[] args = extractor.extractArguments(ctx, emptyBoundRequest());

        List<?> list = assertInstanceOf(List.class, args[0]);
        assertEquals(1, list.size(), "@DefaultValue on an absent FORM collection must yield exactly one entry");
        assertEquals("x", list.get(0));
    }

    // --- 8. Input-policy chain traversal ---

    @Test
    @DisplayName("@FormParam List<String> elements traverse the input-policy chain (today: they don't)")
    void formParamCollection_elementsTraverseInputPolicyChain() throws Exception {
        Method method = FormResource.class.getMethod("list", List.class);
        ResourceMethodMeta.ParamMeta param = formParam("a", List.class, String.class);
        ResourceMethodMeta meta = metaFor(method, List.of(param), List.of(MarkerCanonicalizer.class), List.of());

        UppercasingProcessor processor = new UppercasingProcessor();
        ParameterExtractor extractor = extractorFor(meta, processor);

        RoutingContext ctx = formRoutingContext(Map.of("a", List.of("a", "b")), List.of());
        Object[] args = extractor.extractArguments(ctx, emptyBoundRequest());

        List<?> list = assertInstanceOf(List.class, args[0]);
        assertEquals(
                List.of("A", "B"),
                list,
                "collection elements must traverse the input-policy chain identically to a scalar FORM value");
    }

    // --- 8b. FORM elements traverse the chain in the FORM scalar ORDER (before conversion) ---

    @Test
    @DisplayName("@FormParam Integer and @FormParam List<Integer> both canonicalize before conversion")
    void formParamScalarAndCollection_bothProcessRawValueBeforeConversion() throws Exception {
        // The FORM scalar branch processes the RAW form string and only then converts, so a
        // canonicalizer that normalizes " 5" to "5" makes the scalar convert cleanly. The element path
        // must traverse the same chain in the same ORDER, otherwise the raw " 5" reaches the Integer
        // converter and the collection 400s while its own scalar equivalent succeeds — an asymmetry
        // inside a single source.
        Method scalarMethod = FormResource.class.getMethod("integer", Integer.class);
        ResourceMethodMeta.ParamMeta scalarParam = formParam("a", Integer.class, null);
        ResourceMethodMeta scalarMeta =
                metaFor(scalarMethod, List.of(scalarParam), List.of(MarkerCanonicalizer.class), List.of());
        Object[] scalarArgs = extractorFor(scalarMeta, new TrimmingProcessor())
                .extractArguments(formRoutingContext(Map.of("a", List.of(" 5")), List.of()), emptyBoundRequest());
        assertEquals(5, scalarArgs[0], "a scalar @FormParam Integer canonicalizes the raw value before converting");

        Method listMethod = FormResource.class.getMethod("integerList", List.class);
        ResourceMethodMeta.ParamMeta listParam = formParam("a", List.class, Integer.class);
        ResourceMethodMeta listMeta =
                metaFor(listMethod, List.of(listParam), List.of(MarkerCanonicalizer.class), List.of());
        Object[] listArgs = extractorFor(listMeta, new TrimmingProcessor())
                .extractArguments(formRoutingContext(Map.of("a", List.of(" 5")), List.of()), emptyBoundRequest());

        List<?> list = assertInstanceOf(List.class, listArgs[0]);
        assertEquals(
                List.of(5),
                list,
                "a @FormParam List<Integer> element must traverse the chain in the same order as the scalar");
    }

    // --- 9. Native multipart shapes are unaffected (regression guard) ---

    @Test
    @DisplayName("FileUpload, EntityPart, List<FileUpload>, List<EntityPart> @FormParam targets still bind natively")
    void nativeMultipartListShapes_unaffected() throws Exception {
        // Scalar FileUpload
        Method fileUploadMethod = FormResource.class.getMethod("fileUpload", FileUpload.class);
        FileUpload upload = fileUpload("upload");
        ResourceMethodMeta.ParamMeta fileUploadParam = formParam("upload", FileUpload.class, null);
        ResourceMethodMeta fileUploadMeta = metaFor(fileUploadMethod, List.of(fileUploadParam));
        RoutingContext fileUploadCtx = formRoutingContext(Map.of(), List.of(upload));
        Object[] fileUploadArgs = extractorFor(fileUploadMeta).extractArguments(fileUploadCtx, emptyBoundRequest());
        assertSame(upload, fileUploadArgs[0]);

        // Scalar EntityPart, from a plain text form field
        Method entityPartMethod = FormResource.class.getMethod("entityPart", EntityPart.class);
        ResourceMethodMeta.ParamMeta entityPartParam = formParam("part", EntityPart.class, null);
        ResourceMethodMeta entityPartMeta = metaFor(entityPartMethod, List.of(entityPartParam));
        RoutingContext entityPartCtx = formRoutingContext(Map.of("part", List.of("hello")), List.of());
        Object[] entityPartArgs = extractorFor(entityPartMeta).extractArguments(entityPartCtx, emptyBoundRequest());
        EntityPart part = assertInstanceOf(EntityPart.class, entityPartArgs[0]);
        assertEquals("part", part.getName());

        // List<FileUpload>
        Method fileUploadListMethod = FormResource.class.getMethod("fileUploadList", List.class);
        ResourceMethodMeta.ParamMeta fileUploadListParam = formParam("uploads", List.class, FileUpload.class);
        ResourceMethodMeta fileUploadListMeta = metaFor(fileUploadListMethod, List.of(fileUploadListParam));
        RoutingContext fileUploadListCtx =
                formRoutingContext(Map.of(), List.of(fileUpload("uploads"), fileUpload("uploads")));
        Object[] fileUploadListArgs =
                extractorFor(fileUploadListMeta).extractArguments(fileUploadListCtx, emptyBoundRequest());
        List<?> fileUploadListResult = assertInstanceOf(List.class, fileUploadListArgs[0]);
        assertEquals(2, fileUploadListResult.size());

        // List<EntityPart>
        Method entityPartListMethod = FormResource.class.getMethod("entityPartList", List.class);
        ResourceMethodMeta.ParamMeta entityPartListParam = formParam("parts", List.class, EntityPart.class);
        ResourceMethodMeta entityPartListMeta = metaFor(entityPartListMethod, List.of(entityPartListParam));
        RoutingContext entityPartListCtx = formRoutingContext(Map.of("parts", List.of("v1", "v2")), List.of());
        Object[] entityPartListArgs =
                extractorFor(entityPartListMeta).extractArguments(entityPartListCtx, emptyBoundRequest());
        List<?> entityPartListResult = assertInstanceOf(List.class, entityPartListArgs[0]);
        assertEquals(2, entityPartListResult.size());
    }

    // --- 10. Native multipart collections must be read-only ---

    @Test
    @DisplayName("Native multipart FORM/aggregate collections are read-only; FILE_UPLOADS already is (pinned)")
    @SuppressWarnings("unchecked")
    void nativeMultipartLists_areReadOnly() throws Exception {
        Method fileUploadListMethod = FormResource.class.getMethod("fileUploadList", List.class);
        Method entityPartListMethod = FormResource.class.getMethod("entityPartList", List.class);

        // FORM List<FileUpload> — was mutable (Collectors.toList()) before the read-only contract
        ResourceMethodMeta.ParamMeta fileUploadListParam = formParam("uploads", List.class, FileUpload.class);
        ResourceMethodMeta fileUploadListMeta = metaFor(fileUploadListMethod, List.of(fileUploadListParam));
        RoutingContext fileUploadListCtx = formRoutingContext(Map.of(), List.of(fileUpload("uploads")));
        Object[] fileUploadListArgs =
                extractorFor(fileUploadListMeta).extractArguments(fileUploadListCtx, emptyBoundRequest());
        List<Object> fileUploadList = (List<Object>) assertInstanceOf(List.class, fileUploadListArgs[0]);
        assertThrows(
                UnsupportedOperationException.class,
                () -> fileUploadList.add(fileUpload("extra")),
                "a FORM List<FileUpload> must be materialized read-only");

        // FORM List<EntityPart> — was mutable (new ArrayList<>()) before the read-only contract
        ResourceMethodMeta.ParamMeta entityPartListParam = formParam("parts", List.class, EntityPart.class);
        ResourceMethodMeta entityPartListMeta = metaFor(entityPartListMethod, List.of(entityPartListParam));
        RoutingContext entityPartListCtx = formRoutingContext(Map.of("parts", List.of("v1")), List.of());
        Object[] entityPartListArgs =
                extractorFor(entityPartListMeta).extractArguments(entityPartListCtx, emptyBoundRequest());
        List<Object> entityPartList = (List<Object>) assertInstanceOf(List.class, entityPartListArgs[0]);
        assertThrows(
                UnsupportedOperationException.class,
                () -> entityPartList.add(new FormFieldEntityPart("x", "y")),
                "a FORM List<EntityPart> must be materialized read-only");

        // ENTITY_PARTS aggregate — was mutable (extractAllEntityParts' new ArrayList<>())
        ResourceMethodMeta.ParamMeta entityPartsAggregateParam =
                aggregateParam(ResourceMethodMeta.ParamSource.ENTITY_PARTS, EntityPart.class);
        ResourceMethodMeta entityPartsAggregateMeta = metaFor(entityPartListMethod, List.of(entityPartsAggregateParam));
        RoutingContext entityPartsAggregateCtx = formRoutingContext(Map.of("x", List.of("y")), List.of());
        Object[] entityPartsAggregateArgs =
                extractorFor(entityPartsAggregateMeta).extractArguments(entityPartsAggregateCtx, emptyBoundRequest());
        List<Object> entityPartsAggregate = (List<Object>) assertInstanceOf(List.class, entityPartsAggregateArgs[0]);
        assertThrows(
                UnsupportedOperationException.class,
                () -> entityPartsAggregate.add(new FormFieldEntityPart("x", "y")),
                "the ENTITY_PARTS aggregate must be materialized read-only");

        // FILE_UPLOADS aggregate — already read-only today (List.copyOf); pinned against regression
        ResourceMethodMeta.ParamMeta fileUploadsAggregateParam =
                aggregateParam(ResourceMethodMeta.ParamSource.FILE_UPLOADS, FileUpload.class);
        ResourceMethodMeta fileUploadsAggregateMeta = metaFor(fileUploadListMethod, List.of(fileUploadsAggregateParam));
        RoutingContext fileUploadsAggregateCtx = formRoutingContext(Map.of(), List.of(fileUpload("uploads")));
        Object[] fileUploadsAggregateArgs =
                extractorFor(fileUploadsAggregateMeta).extractArguments(fileUploadsAggregateCtx, emptyBoundRequest());
        List<Object> fileUploadsAggregate = (List<Object>) assertInstanceOf(List.class, fileUploadsAggregateArgs[0]);
        assertThrows(
                UnsupportedOperationException.class,
                () -> fileUploadsAggregate.add(fileUpload("extra")),
                "the FILE_UPLOADS aggregate is already read-only via List.copyOf (regression guard)");
    }

    // --- 11. Non-List native multipart shape rejected at startup ---

    @Test
    @DisplayName("@FormParam Set<FileUpload> is rejected at startup as an unsupported native multipart shape")
    void nativeMultipartNonListShape_rejectedAtStartup() {
        assertNonListNativeMultipartShapeRejected(
                new NativeMultipartSetResource(),
                "a Set<FileUpload> @FormParam has no native materialization and must be rejected at startup");
    }

    @Test
    @DisplayName("@FormParam Set<EntityPart> is rejected at startup as an unsupported native multipart shape")
    void nativeMultipartNonListEntityPartShape_rejectedAtStartup() {
        assertNonListNativeMultipartShapeRejected(
                new NativeMultipartEntityPartSetResource(),
                "a Set<EntityPart> @FormParam has no native materialization and must be rejected at startup");
    }

    /**
     * Asserts that registering {@code resource} fails startup naming
     * {@code UNSUPPORTED_MULTIPART_COLLECTION_SHAPE}.
     *
     * <p>Shared by both native element types on purpose: the rule is "native target, {@code List}
     * only", and {@code RouteValidator} reaches that conclusion through two separate expressions —
     * {@code isSupportedFileUploadTarget} for {@code FileUpload} and a direct
     * {@code type() == List.class} test for {@code EntityPart}. Exercising only one of them would
     * leave the other free to drift.
     *
     * <p>The assertion matches the violation type by NAME rather than by referencing the enum
     * constant, because this test was authored before the constant existed. It is left as a string
     * deliberately: the message text is what an application developer actually sees at startup, so
     * pinning it also pins the diagnostic.
     */
    private static void assertNonListNativeMultipartShapeRejected(Object resource, String because) {
        Vertx vertx = Vertx.vertx();
        try {
            JaxRsRouteRegistrar registrar = new JaxRsRouteRegistrar();
            Router router = Router.router(vertx);

            RouteRegistrationException ex = assertThrows(
                    RouteRegistrationException.class,
                    () -> RegistrarTestSupport.registerAll(
                            registrar,
                            Set.of(resource),
                            router,
                            RegistrarTestSupport.TEST_MOUNT_META,
                            List.of(),
                            List.of(),
                            null,
                            false,
                            List.of(),
                            List.of(),
                            "OFF",
                            null,
                            null,
                            null,
                            false),
                    because);

            assertTrue(
                    ex.getMessage().contains("UNSUPPORTED_MULTIPART_COLLECTION_SHAPE"),
                    "the violation must name the new UNSUPPORTED_MULTIPART_COLLECTION_SHAPE type (was: "
                            + ex.getMessage() + ")");
        } finally {
            vertx.close();
        }
    }
}
