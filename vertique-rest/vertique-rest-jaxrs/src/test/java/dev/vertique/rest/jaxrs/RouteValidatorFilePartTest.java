// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.request.FilePart;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Vertx;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.EntityPart;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies fail-closed startup diagnostics for invalid {@link FilePart} declarations. */
class RouteValidatorFilePartTest {

    private JaxRsRouteRegistrar registrar;
    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        registrar = new JaxRsRouteRegistrar();
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    @DisplayName("@FilePart on a non-file parameter fails startup with a mapped violation")
    void filePartOnNonFileParamFailsStartup() {
        assertInvalidFilePart(new NonFilePartResource(), "nonFilePart", "description");
    }

    @Test
    @DisplayName("@FilePart on an EntityPart parameter fails startup with a mapped violation")
    void filePartOnEntityPartParamFailsStartup() {
        assertInvalidFilePart(new EntityPartResource(), "entityPart", "document");
    }

    @Test
    @DisplayName("Every malformed @FilePart value fails startup with a mapped violation")
    void invalidFilePartValuesFailStartup() {
        assertAll(
                () -> assertInvalidFilePart(new ZeroMaxSizeResource(), "zeroMaxSize", "file"),
                () -> assertInvalidFilePart(new AnyTypeWildcardResource(), "anyTypeWildcard", "file"),
                () -> assertInvalidFilePart(new ParameterizedTypeResource(), "parameterizedType", "file"),
                () -> assertInvalidFilePart(new WildcardTypeResource(), "wildcardType", "file"),
                () -> assertInvalidFilePart(new ExtraSlashResource(), "extraSlash", "file"),
                () -> assertInvalidFilePart(new ControlCharacterResource(), "controlCharacter", "file"),
                () -> assertInvalidFilePart(new EmptyAllowedTypeResource(), "emptyAllowedType", "file"));
    }

    @Test
    @DisplayName("Overlapping constrained file declarations fail startup with a mapped violation")
    void overlappingConstrainedDeclarationsFailStartup() {
        assertAll(
                () -> assertInvalidFilePart(new SameNamedOverlapResource(), "sameNamedOverlap", "avatar"),
                () -> assertInvalidFilePart(new AggregateNamedOverlapResource(), "aggregateNamedOverlap", "avatar"));
    }

    private void assertInvalidFilePart(Object resource, String operationId, String parameterName) {
        RouteRegistrationException exception = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(resource),
                        router,
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
                        false));

        assertEquals(1, exception.violations().size());
        RouteRegistrationViolation violation = exception.violations().getFirst();
        assertEquals(operationId, violation.operationId());
        assertEquals(
                RouteRegistrationViolation.ViolationType.valueOf("INVALID_FILE_PART_DECLARATION"), violation.type());
        String expectedPrefix = "@FilePart on parameter '" + parameterName + "' of " + operationId + ": ";
        assertTrue(
                violation.message().startsWith(expectedPrefix),
                () -> "Expected diagnostic prefix <" + expectedPrefix + "> but was <" + violation.message() + ">");
    }

    @Path("/non-file")
    static class NonFilePartResource {

        @POST
        @Operation(operationId = "nonFilePart")
        String upload(@FormParam("description") @FilePart String description) {
            return description;
        }
    }

    @Path("/entity-part")
    static class EntityPartResource {

        @POST
        @Operation(operationId = "entityPart")
        String upload(@FormParam("document") @FilePart EntityPart document) {
            return "ok";
        }
    }

    @Path("/zero-max-size")
    static class ZeroMaxSizeResource {

        @POST
        @Operation(operationId = "zeroMaxSize")
        String upload(@FormParam("file") @FilePart(maxSizeBytes = 0) FileUpload file) {
            return "ok";
        }
    }

    @Path("/any-type-wildcard")
    static class AnyTypeWildcardResource {

        @POST
        @Operation(operationId = "anyTypeWildcard")
        String upload(@FormParam("file") @FilePart(allowedTypes = "*/*") FileUpload file) {
            return "ok";
        }
    }

    @Path("/parameterized-type")
    static class ParameterizedTypeResource {

        @POST
        @Operation(operationId = "parameterizedType")
        String upload(@FormParam("file") @FilePart(allowedTypes = "image/png; q=1") FileUpload file) {
            return "ok";
        }
    }

    @Path("/wildcard-type")
    static class WildcardTypeResource {

        @POST
        @Operation(operationId = "wildcardType")
        String upload(@FormParam("file") @FilePart(allowedTypes = "*/png") FileUpload file) {
            return "ok";
        }
    }

    @Path("/extra-slash")
    static class ExtraSlashResource {

        @POST
        @Operation(operationId = "extraSlash")
        String upload(@FormParam("file") @FilePart(allowedTypes = "image/png/extra") FileUpload file) {
            return "ok";
        }
    }

    @Path("/control-character")
    static class ControlCharacterResource {

        @POST
        @Operation(operationId = "controlCharacter")
        String upload(@FormParam("file") @FilePart(allowedTypes = "image/\t") FileUpload file) {
            return "ok";
        }
    }

    @Path("/empty-allowed-type")
    static class EmptyAllowedTypeResource {

        @POST
        @Operation(operationId = "emptyAllowedType")
        String upload(@FormParam("file") @FilePart(allowedTypes = "") FileUpload file) {
            return "ok";
        }
    }

    @Path("/same-named-overlap")
    static class SameNamedOverlapResource {

        @POST
        @Operation(operationId = "sameNamedOverlap")
        String upload(
                @FormParam("avatar") @FilePart(maxSizeBytes = 1024) FileUpload first,
                @FormParam("avatar") @FilePart(allowedTypes = "image/png") FileUpload second) {
            return "ok";
        }
    }

    @Path("/aggregate-named-overlap")
    static class AggregateNamedOverlapResource {

        @POST
        @Operation(operationId = "aggregateNamedOverlap")
        String upload(
                @FilePart(maxSizeBytes = 4096) List<FileUpload> files,
                @FormParam("avatar") @FilePart(allowedTypes = "image/png") FileUpload avatar) {
            return "ok";
        }
    }
}
