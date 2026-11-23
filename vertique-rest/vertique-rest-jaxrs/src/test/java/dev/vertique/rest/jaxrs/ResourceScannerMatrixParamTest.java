// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.security.SecurityPolicyViolation;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterizes how the resource scanner treats {@code @MatrixParam}: the annotation is not a
 * recognized parameter source on a server resource method, so a parameter that carries it falls
 * through to the request body.
 */
class ResourceScannerMatrixParamTest {

    /** Payload type for the fixtures. */
    record Foo(String name) {}

    @Path("/matrix-body")
    static class MatrixOnlyResource {

        /**
         * One matrix-annotated parameter.
         *
         * @param foo bound as the request body
         */
        @POST
        public void create(@MatrixParam("x") Foo foo) {}
    }

    @Path("/matrix-and-body")
    static class MatrixAndBodyResource {

        /**
         * A matrix-annotated parameter next to an unannotated one.
         *
         * @param a falls through to body
         * @param body body
         */
        @POST
        public void create(@MatrixParam("x") String a, Foo body) {}
    }

    @Test
    @DisplayName("a @MatrixParam parameter is scanned as the BODY source")
    void matrixParamIsScannedAsBody() {
        List<ResourceMethodMeta> meta = scan(new MatrixOnlyResource());

        assertEquals(1, meta.size());
        List<ResourceMethodMeta.ParamMeta> params = meta.get(0).params();
        assertEquals(1, params.size());
        assertEquals(ResourceMethodMeta.ParamSource.BODY, params.get(0).source());
        assertEquals(Foo.class, params.get(0).type());
        assertTrue(RouteValidator.validateMethodParams(meta.get(0)).isEmpty());
    }

    @Test
    @DisplayName("a @MatrixParam parameter next to an unannotated body parameter fails with MULTIPLE_BODY_PARAMS")
    void matrixParamPlusBodyParamFailsWithMultipleBodyParams() {
        List<ResourceMethodMeta> meta = scan(new MatrixAndBodyResource());

        List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta.get(0));
        assertEquals(1, violations.size());
        assertEquals(
                RouteRegistrationViolation.ViolationType.MULTIPLE_BODY_PARAMS,
                violations.get(0).type());
    }

    private static List<ResourceMethodMeta> scan(Object resource) {
        List<SecurityPolicyViolation> securityViolations = new ArrayList<>();
        List<ResourceMethodMeta> meta =
                new ResourceScanner(new SecurityPolicyBuilder()).scanResource(resource, securityViolations);
        assertTrue(securityViolations.isEmpty());
        return meta;
    }
}
