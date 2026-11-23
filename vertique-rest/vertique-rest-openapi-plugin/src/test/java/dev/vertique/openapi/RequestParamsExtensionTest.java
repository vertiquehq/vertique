// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.request.RequestParams;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.parameters.Parameter;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link RequestParamsExtension}'s expansion of {@code @RequestParams} types. */
class RequestParamsExtensionTest {

    @RequestParams
    record PathDefault(@PathParam("id") @DefaultValue("7") String id) {}

    @RequestParams
    record PathNullable(@PathParam("id") @Nullable String id) {}

    @Schema(name = "RenamedFilter")
    public static class Filter {
        public String term;
    }

    @RequestParams
    record WithFilter(@QueryParam("f") Filter filter) {}

    private static List<Parameter> expand(Class<?> type, Components components) {
        var result = new RequestParamsExtension()
                .extractParameters(
                        List.<Annotation>of(),
                        type,
                        new java.util.HashSet<>(),
                        components,
                        null,
                        null,
                        false,
                        null,
                        java.util.Collections.emptyIterator());
        assertNotNull(result);
        return result.parameters;
    }

    @Test
    @DisplayName("a path parameter with @DefaultValue stays required and keeps the schema default")
    void pathParamWithDefaultValue_isRequired() {
        List<Parameter> params = expand(PathDefault.class, new Components());

        assertEquals(1, params.size());
        assertEquals(Boolean.TRUE, params.get(0).getRequired());
        assertEquals("7", params.get(0).getSchema().getDefault());
    }

    @Test
    @DisplayName("a path parameter with @Nullable stays required")
    void pathParamWithNullable_isRequired() {
        List<Parameter> params = expand(PathNullable.class, new Components());

        assertEquals(1, params.size());
        assertEquals(Boolean.TRUE, params.get(0).getRequired());
    }

    @Test
    @DisplayName("a member type renamed with @Schema(name) gets a $ref to the registered component")
    void renamedMemberType_refMatchesRegisteredComponent() {
        Components components = new Components();

        List<Parameter> params = expand(WithFilter.class, components);

        assertEquals(1, params.size());
        assertEquals(
                "#/components/schemas/RenamedFilter", params.get(0).getSchema().get$ref());
        assertTrue(components.getSchemas().containsKey("RenamedFilter"));
    }
}
