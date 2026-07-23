// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the reflective {@link ParameterExtractor} materializes the declared collection type for a
 * multi-value {@code @QueryParam} bound (by {@code DefaultBoundRequest}) as a {@link JsonArray} of raw
 * strings: a {@code Set<String>} param yields a deduplicating {@link Set} with every distinct value,
 * and an {@code Integer[]} param yields an {@code Integer[]} of every coerced value. Both prove the
 * collection path binds ALL values rather than only the first (the bug fixed by extending
 * {@code ResourceScanner.resolveComponentType} to recognize Set/array shapes).
 */
class SetAndArrayQueryParamBindTest {

    /** Resource exposing a {@code Set<String>} and an {@code Integer[]} query param. */
    static final class CollectionResource {
        @SuppressWarnings("unused")
        public String tags(Set<String> tags) {
            return String.valueOf(tags);
        }

        @SuppressWarnings("unused")
        public String ids(Integer[] ids) {
            return String.valueOf(ids.length);
        }

        @SuppressWarnings("unused")
        public String sortedIds(java.util.SortedSet<Integer> ids) {
            return String.valueOf(ids);
        }

        @SuppressWarnings("unused")
        public String navigableIds(java.util.NavigableSet<Integer> ids) {
            return String.valueOf(ids);
        }
    }

    private static ResourceMethodMeta metaFor(
            Method method, ResourceMethodMeta.ParamMeta param, Class<?> responseType) {
        return new ResourceMethodMeta(
                new CollectionResource(),
                method,
                method.getName(),
                "GET",
                "/things",
                List.of(param),
                responseType,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static ParameterExtractor extractorFor(ResourceMethodMeta meta) {
        List<RequestBodyDecoder> decoders = List.of(new JsonRequestBodyDecoder());
        return new ParameterExtractor(meta, decoders, new RestContextResolution(Set.of()));
    }

    /** A minimal {@link BoundRequest} stub whose query map holds the supplied entries. */
    private static BoundRequest boundQuery(Map<String, RequestValue> query) {
        return new BoundRequest() {
            @Override
            public Map<String, RequestValue> pathParameters() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> query() {
                return query;
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

    @Test
    @DisplayName("Set<String> @QueryParam binds all values, deduplicated, into a Set")
    void setQueryParamBindsAllValues() throws Exception {
        Method tags = CollectionResource.class.getMethod("tags", Set.class);
        ResourceMethodMeta.ParamMeta param =
                new ResourceMethodMeta.ParamMeta("tags", ResourceMethodMeta.ParamSource.QUERY, Set.class, String.class);
        ResourceMethodMeta meta = metaFor(tags, param, String.class);
        ParameterExtractor extractor = extractorFor(meta);

        // DefaultBoundRequest binds repeated query values as a JsonArray of raw strings.
        JsonArray bound = new JsonArray().add("a").add("b").add("a");
        BoundRequest req = boundQuery(Map.of("tags", RequestValue.of(bound)));

        Object[] args = extractor.extractArguments(null, req);

        assertEquals(1, args.length);
        Set<?> set = assertInstanceOf(Set.class, args[0]);
        assertEquals(2, set.size(), "duplicate 'a' must be collapsed by the Set");
        assertTrue(set.contains("a"));
        assertTrue(set.contains("b"));
    }

    @Test
    @DisplayName("Integer[] @QueryParam binds all values, coerced, into an Integer[]")
    void arrayQueryParamBindsAllValues() throws Exception {
        Method ids = CollectionResource.class.getMethod("ids", Integer[].class);
        ResourceMethodMeta.ParamMeta param = new ResourceMethodMeta.ParamMeta(
                "ids", ResourceMethodMeta.ParamSource.QUERY, Integer[].class, Integer.class);
        ResourceMethodMeta meta = metaFor(ids, param, String.class);
        ParameterExtractor extractor = extractorFor(meta);

        JsonArray bound = new JsonArray().add("1").add("2").add("3");
        BoundRequest req = boundQuery(Map.of("ids", RequestValue.of(bound)));

        Object[] args = extractor.extractArguments(null, req);

        assertEquals(1, args.length);
        Integer[] array = assertInstanceOf(Integer[].class, args[0]);
        assertEquals(3, array.length);
        assertEquals(Integer.valueOf(1), array[0]);
        assertEquals(Integer.valueOf(2), array[1]);
        assertEquals(Integer.valueOf(3), array[2]);
    }

    @Test
    @DisplayName("SortedSet<Integer> @QueryParam binds all values into a sorted, Method.invoke-assignable SortedSet")
    void sortedSetQueryParamBindsTreeSet() throws Exception {
        // ResourceScanner.isSupportedCollectionRawType accepts SortedSet and keeps the declared type,
        // so coerceCollection must materialize a TreeSet (a SortedSet) — a LinkedHashSet is NOT
        // assignable to a SortedSet param and would make Method.invoke throw IllegalArgumentException.
        Method sortedIds = CollectionResource.class.getMethod("sortedIds", java.util.SortedSet.class);
        ResourceMethodMeta.ParamMeta param = new ResourceMethodMeta.ParamMeta(
                "ids", ResourceMethodMeta.ParamSource.QUERY, java.util.SortedSet.class, Integer.class);
        ResourceMethodMeta meta = metaFor(sortedIds, param, String.class);
        ParameterExtractor extractor = extractorFor(meta);

        JsonArray bound = new JsonArray().add("3").add("1").add("2");
        BoundRequest req = boundQuery(Map.of("ids", RequestValue.of(bound)));

        Object[] args = extractor.extractArguments(null, req);

        assertEquals(1, args.length);
        java.util.SortedSet<?> set = assertInstanceOf(java.util.SortedSet.class, args[0]);
        assertEquals(List.of(1, 2, 3), List.copyOf(set), "a SortedSet must hold sorted, coerced Integers");
        // The arg must be reflectively assignable to the SortedSet param (no IllegalArgumentException).
        assertEquals("[1, 2, 3]", sortedIds.invoke(new CollectionResource(), args[0]));
    }

    @Test
    @DisplayName(
            "NavigableSet<Integer> @QueryParam binds all values into a sorted, Method.invoke-assignable NavigableSet")
    void navigableSetQueryParamBindsTreeSet() throws Exception {
        Method navigableIds = CollectionResource.class.getMethod("navigableIds", java.util.NavigableSet.class);
        ResourceMethodMeta.ParamMeta param = new ResourceMethodMeta.ParamMeta(
                "ids", ResourceMethodMeta.ParamSource.QUERY, java.util.NavigableSet.class, Integer.class);
        ResourceMethodMeta meta = metaFor(navigableIds, param, String.class);
        ParameterExtractor extractor = extractorFor(meta);

        JsonArray bound = new JsonArray().add("3").add("1").add("2");
        BoundRequest req = boundQuery(Map.of("ids", RequestValue.of(bound)));

        Object[] args = extractor.extractArguments(null, req);

        assertEquals(1, args.length);
        java.util.NavigableSet<?> set = assertInstanceOf(java.util.NavigableSet.class, args[0]);
        assertEquals(List.of(1, 2, 3), List.copyOf(set), "a NavigableSet must hold sorted, coerced Integers");
        assertEquals("[1, 2, 3]", navigableIds.invoke(new CollectionResource(), args[0]));
    }
}
