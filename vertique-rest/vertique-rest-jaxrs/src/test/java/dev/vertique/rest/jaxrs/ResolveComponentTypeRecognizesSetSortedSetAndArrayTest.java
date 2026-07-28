// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.ext.web.FileUpload;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.util.Collection;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ResourceScanner#resolveComponentType} recognizes every multi-value parameter
 * shape — {@code List<T>}, {@code Set<T>}, {@code SortedSet<T>}, {@code NavigableSet<T>},
 * {@code Collection<T>}, and array ({@code T[]}) — by resolving the element type so the downstream
 * collection-binding/validation path is engaged (gated on {@code componentType != null}). It also
 * confirms the special cases that must NOT be treated as scalar collections: a plain scalar param
 * resolves {@code null}, an unannotated {@code List<FileUpload>} keeps its FILE_UPLOADS handling
 * (not a scalar {@code componentType} collection on a non-annotated param), and a {@code byte[]}
 * body param does not become a multi-value collection.
 *
 * <p>Also covers the FORM analogues (plan §4, S5): unlike QUERY, today's {@code ResourceScanner}
 * FORM branch (F5) rewrites every collection-shaped {@code @FormParam}'s declared type to
 * {@code List.class}, so a {@code Set}/{@code SortedSet}/{@code NavigableSet}/{@code Collection}/
 * array {@code @FormParam} incorrectly reports {@code type() == List.class} today. The FORM tests
 * below pin the fix: the declared type must be preserved exactly like QUERY.
 */
class ResolveComponentTypeRecognizesSetSortedSetAndArrayTest {

    // --- Test resource fixtures ---

    /** Resource declaring one method per multi-value (and scalar) shape on {@code @QueryParam}. */
    @Path("/collections")
    @PermitAll
    static class CollectionParamResource {

        /**
         * @return ok
         */
        @GET
        @Path("/list")
        public String listParam(@QueryParam("v") List<Integer> v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @GET
        @Path("/set")
        public String setParam(@QueryParam("v") Set<Integer> v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @GET
        @Path("/sorted-set")
        public String sortedSetParam(@QueryParam("v") SortedSet<String> v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @GET
        @Path("/navigable-set")
        public String navigableSetParam(@QueryParam("v") NavigableSet<String> v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @GET
        @Path("/collection")
        public String collectionParam(@QueryParam("v") Collection<String> v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @GET
        @Path("/array")
        public String arrayParam(@QueryParam("v") Integer[] v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @GET
        @Path("/scalar")
        public String scalarParam(@QueryParam("v") String v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @GET
        @Path("/byte-array")
        public String byteArrayBody(byte[] body) {
            return "ok";
        }
    }

    /** Resource with an unannotated {@code List<FileUpload>} param (multipart file-upload list). */
    @Path("/uploads")
    @PermitAll
    static class FileUploadListResource {

        /**
         * @return ok
         */
        @GET
        @Path("/files")
        public String files(List<FileUpload> files) {
            return "ok";
        }
    }

    /** Resource declaring one method per multi-value shape on {@code @FormParam} (FORM source). */
    @Path("/form-collections")
    @PermitAll
    static class FormCollectionParamResource {

        /**
         * @return ok
         */
        @POST
        @Path("/set")
        public String setParam(@FormParam("v") Set<Integer> v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @POST
        @Path("/sorted-set")
        public String sortedSetParam(@FormParam("v") SortedSet<String> v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @POST
        @Path("/navigable-set")
        public String navigableSetParam(@FormParam("v") NavigableSet<String> v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @POST
        @Path("/collection")
        public String collectionParam(@FormParam("v") Collection<String> v) {
            return "ok";
        }

        /**
         * @return ok
         */
        @POST
        @Path("/array")
        public String arrayParam(@FormParam("v") Integer[] v) {
            return "ok";
        }
    }

    // --- Helpers ---

    private ResourceScanner scanner() {
        return new ResourceScanner(new SecurityPolicyBuilder());
    }

    private ResourceMethodMeta.ParamMeta firstParamOf(Object resource, String methodName) {
        for (ResourceMethodMeta meta : scanner().scanResource(resource)) {
            if (meta.method().getName().equals(methodName)) {
                return meta.params().get(0);
            }
        }
        throw new AssertionError("method not found: " + methodName);
    }

    // --- Tests ---

    @Test
    @DisplayName("List<Integer> @QueryParam resolves Integer component type (existing behavior)")
    void listResolvesComponentType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new CollectionParamResource(), "listParam");
        assertEquals(List.class, pm.type());
        assertEquals(Integer.class, pm.componentType());
    }

    @Test
    @DisplayName("Set<Integer> @QueryParam resolves Integer component type and Set declared type")
    void setResolvesComponentType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new CollectionParamResource(), "setParam");
        assertEquals(Set.class, pm.type());
        assertEquals(Integer.class, pm.componentType());
    }

    @Test
    @DisplayName("SortedSet<String> @QueryParam resolves String component type")
    void sortedSetResolvesComponentType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new CollectionParamResource(), "sortedSetParam");
        assertEquals(SortedSet.class, pm.type());
        assertEquals(String.class, pm.componentType());
    }

    @Test
    @DisplayName("NavigableSet<String> @QueryParam resolves String component type")
    void navigableSetResolvesComponentType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new CollectionParamResource(), "navigableSetParam");
        assertEquals(NavigableSet.class, pm.type());
        assertEquals(String.class, pm.componentType());
    }

    @Test
    @DisplayName("Collection<String> @QueryParam resolves String component type")
    void collectionResolvesComponentType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new CollectionParamResource(), "collectionParam");
        assertEquals(Collection.class, pm.type());
        assertEquals(String.class, pm.componentType());
    }

    @Test
    @DisplayName("Integer[] @QueryParam resolves Integer component type and array declared type")
    void arrayResolvesComponentType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new CollectionParamResource(), "arrayParam");
        assertEquals(Integer[].class, pm.type());
        assertEquals(Integer.class, pm.componentType());
    }

    @Test
    @DisplayName("Scalar String @QueryParam has null component type")
    void scalarResolvesNullComponentType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new CollectionParamResource(), "scalarParam");
        assertEquals(String.class, pm.type());
        assertNull(pm.componentType());
    }

    @Test
    @DisplayName("byte[] body param is not treated as a multi-value scalar collection")
    void byteArrayBodyIsNotScalarCollection() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new CollectionParamResource(), "byteArrayBody");
        assertEquals(byte[].class, pm.type());
        assertNull(pm.componentType());
        assertEquals(ResourceMethodMeta.ParamSource.BODY, pm.source());
    }

    @Test
    @DisplayName(
            "Unannotated List<FileUpload> stays a FILE_UPLOADS param (FileUpload component, not a scalar collection)")
    void fileUploadListIsNotScalarCollection() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new FileUploadListResource(), "files");
        assertEquals(ResourceMethodMeta.ParamSource.FILE_UPLOADS, pm.source());
        assertNotNull(pm.componentType());
        assertEquals(FileUpload.class, pm.componentType());
    }

    // --- FORM analogues (plan §4, S5) ---

    @Test
    @DisplayName("Set<Integer> @FormParam preserves the declared Set type (today: rewritten to List)")
    void formSetPreservesDeclaredType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new FormCollectionParamResource(), "setParam");
        assertEquals(Set.class, pm.type());
        assertEquals(Integer.class, pm.componentType());
    }

    @Test
    @DisplayName("SortedSet<String> @FormParam preserves the declared SortedSet type (today: rewritten to List)")
    void formSortedSetPreservesDeclaredType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new FormCollectionParamResource(), "sortedSetParam");
        assertEquals(SortedSet.class, pm.type());
        assertEquals(String.class, pm.componentType());
    }

    @Test
    @DisplayName("NavigableSet<String> @FormParam preserves the declared NavigableSet type (today: rewritten to List)")
    void formNavigableSetPreservesDeclaredType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new FormCollectionParamResource(), "navigableSetParam");
        assertEquals(NavigableSet.class, pm.type());
        assertEquals(String.class, pm.componentType());
    }

    @Test
    @DisplayName("Collection<String> @FormParam preserves the declared Collection type (today: rewritten to List)")
    void formCollectionPreservesDeclaredType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new FormCollectionParamResource(), "collectionParam");
        assertEquals(Collection.class, pm.type());
        assertEquals(String.class, pm.componentType());
    }

    @Test
    @DisplayName("Integer[] @FormParam preserves the declared array type (today: rewritten to List)")
    void formArrayPreservesDeclaredType() {
        ResourceMethodMeta.ParamMeta pm = firstParamOf(new FormCollectionParamResource(), "arrayParam");
        assertEquals(Integer[].class, pm.type());
        assertEquals(Integer.class, pm.componentType());
    }
}
