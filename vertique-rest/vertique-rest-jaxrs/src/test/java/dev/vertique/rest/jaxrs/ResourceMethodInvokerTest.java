// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.annotation.Nullable;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParameterExtractor#resolveComponentParam(RecordComponent)}.
 *
 * <p>Exercises the package-private method directly (same package) to verify that
 * JAX-RS parameter annotations on record components are correctly mapped to
 * {@link ResourceMethodMeta.ParamMeta} instances.
 */
class ResourceMethodInvokerTest {

    // --- Test records ---

    record QueryRecord(
            @QueryParam("page") @Nullable Integer page,
            @QueryParam("size") Integer size) {}

    record PathRecord(@PathParam("id") String id) {}

    record HeaderRecord(@HeaderParam("X-Token") String token) {}

    record CookieRecord(@CookieParam("session") String session) {}

    record FormRecord(@FormParam("field") String field) {}

    record DefaultValueRecord(
            @QueryParam("limit") @DefaultValue("10") Integer limit) {}

    record NoAnnotationRecord(String ignored) {}

    // --- Tests ---

    @Test
    @DisplayName("resolveComponentParam: @QueryParam maps to QUERY source")
    void queryParam() {
        RecordComponent component = QueryRecord.class.getRecordComponents()[0]; // page
        ResourceMethodMeta.ParamMeta meta = ParameterExtractor.resolveComponentParam(component);
        assertNotNull(meta);
        assertEquals("page", meta.name());
        assertEquals(ResourceMethodMeta.ParamSource.QUERY, meta.source());
        assertEquals(Integer.class, meta.type());
        assertNull(meta.defaultValue());
    }

    @Test
    @DisplayName("resolveComponentParam: @PathParam maps to PATH source")
    void pathParam() {
        RecordComponent component = PathRecord.class.getRecordComponents()[0]; // id
        ResourceMethodMeta.ParamMeta meta = ParameterExtractor.resolveComponentParam(component);
        assertNotNull(meta);
        assertEquals("id", meta.name());
        assertEquals(ResourceMethodMeta.ParamSource.PATH, meta.source());
        assertEquals(String.class, meta.type());
    }

    @Test
    @DisplayName("resolveComponentParam: @HeaderParam maps to HEADER source")
    void headerParam() {
        RecordComponent component = HeaderRecord.class.getRecordComponents()[0]; // token
        ResourceMethodMeta.ParamMeta meta = ParameterExtractor.resolveComponentParam(component);
        assertNotNull(meta);
        assertEquals("X-Token", meta.name());
        assertEquals(ResourceMethodMeta.ParamSource.HEADER, meta.source());
    }

    @Test
    @DisplayName("resolveComponentParam: @CookieParam maps to COOKIE source")
    void cookieParam() {
        RecordComponent component = CookieRecord.class.getRecordComponents()[0]; // session
        ResourceMethodMeta.ParamMeta meta = ParameterExtractor.resolveComponentParam(component);
        assertNotNull(meta);
        assertEquals("session", meta.name());
        assertEquals(ResourceMethodMeta.ParamSource.COOKIE, meta.source());
    }

    @Test
    @DisplayName("resolveComponentParam: @FormParam maps to FORM source")
    void formParam() {
        RecordComponent component = FormRecord.class.getRecordComponents()[0]; // field
        ResourceMethodMeta.ParamMeta meta = ParameterExtractor.resolveComponentParam(component);
        assertNotNull(meta);
        assertEquals("field", meta.name());
        assertEquals(ResourceMethodMeta.ParamSource.FORM, meta.source());
    }

    @Test
    @DisplayName("resolveComponentParam: @DefaultValue is captured")
    void defaultValue() {
        RecordComponent component = DefaultValueRecord.class.getRecordComponents()[0]; // limit
        ResourceMethodMeta.ParamMeta meta = ParameterExtractor.resolveComponentParam(component);
        assertNotNull(meta);
        assertEquals("limit", meta.name());
        assertEquals("10", meta.defaultValue());
    }

    @Test
    @DisplayName("resolveComponentParam: unannotated component returns null")
    void noAnnotation() {
        RecordComponent component = NoAnnotationRecord.class.getRecordComponents()[0]; // ignored
        assertNull(ParameterExtractor.resolveComponentParam(component));
    }
}
