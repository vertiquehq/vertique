// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.routing.StubOperationDescriptor;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Seam proof for {@link OperationSchemaSource}: the SPI is satisfiable by a hand-rolled test double
 * with no victools dependency on the test classpath, and the SPI source file references no rest-jaxrs
 * internal {@code ResourceMethodMeta} type.
 */
class OperationSchemaSourceTest {

    /** Minimal {@link JaxRsOperationDescriptor} stub with no parameters or body. */
    private static JaxRsOperationDescriptor emptyOp() {
        return StubOperationDescriptor.builder()
                .operationId("getThing")
                .httpMethod("GET")
                .routeTemplate("/things")
                .build();
    }

    /** The reserved {@code vertique} floor profile, the effective profile of an unannotated operation. */
    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    @Test
    @DisplayName("OperationSchemaSource seam is satisfiable by a test double returning a hardcoded query-param schema")
    void operationSchemaSourceSeamProvenByTestDouble() {
        OperationSchemaSource source = (op, profile) -> OperationSchemas.builder()
                .parameterSchema(ParamLocation.QUERY, "age", new JsonObject().put("minimum", 18))
                .build();

        JaxRsOperationDescriptor d = emptyOp();
        Optional<JsonObject> ageSchema =
                source.schemasFor(d, vertiqueProfile()).parameterSchema(ParamLocation.QUERY, "age");

        assertEquals(18, ageSchema.orElseThrow().getInteger("minimum"));
    }

    @Test
    @DisplayName("OperationSchemaSource source file contains no ResourceMethodMeta reference")
    void operationSchemaSourceHasNoResourceMethodMetaReference() throws Exception {
        Path spi = Path.of("src/main/java/dev/vertique/rest/jaxrs/validation/OperationSchemaSource.java");
        String source = Files.readString(spi);
        assertFalse(
                source.contains("ResourceMethodMeta"),
                "OperationSchemaSource SPI must not reference the internal ResourceMethodMeta type");
    }
}
