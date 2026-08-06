// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.routing.StubOperationDescriptor;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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

    @Test
    @DisplayName("OperationSchemaSource seam is satisfiable by a test double returning a hardcoded query-param schema")
    void operationSchemaSourceSeamProvenByTestDouble() {
        OperationSchemaSource source = op -> OperationSchemas.builder()
                .parameterSchema(ParamLocation.QUERY, "age", new JsonObject().put("minimum", 18))
                .build();

        JaxRsOperationDescriptor d = emptyOp();
        Optional<JsonObject> ageSchema = source.schemasFor(d).parameterSchema(ParamLocation.QUERY, "age");

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
