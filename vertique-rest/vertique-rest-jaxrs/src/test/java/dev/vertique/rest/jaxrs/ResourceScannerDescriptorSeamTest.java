// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.runtime.fixture.BrokenDescriptorResource;
import dev.vertique.rest.jaxrs.runtime.fixture.SeamResource;
import dev.vertique.rest.jaxrs.runtime.fixture.SeamResource_JaxRsDescriptor;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the descriptor fast-path / reflective-fallback dispatch seam introduced in
 * {@link ResourceScanner#scanResource(Object, List)}.
 *
 * <p>Three contract points are tested:
 * <ul>
 *   <li><strong>Hit</strong> — when a generated {@code _JaxRsDescriptor} companion is present,
 *       {@code scanResource} delegates to it and returns its result verbatim (the sentinel
 *       operationId produced by {@link SeamResource_JaxRsDescriptor} proves the fast-path was
 *       taken, not the reflective walk).</li>
 *   <li><strong>Miss</strong> — when no companion exists, the reflective walk runs and produces
 *       the expected metadata from the resource class's annotations.</li>
 *   <li><strong>Broken</strong> — when a companion exists but its constructor throws, the
 *       exception propagates from {@code scanResource} and is NOT silently masked as a reflective
 *       fallback (a broken generated class is a build defect).</li>
 * </ul>
 */
class ResourceScannerDescriptorSeamTest {

    // --- Hit: companion present ---

    @Nested
    @DisplayName("descriptor hit — companion present on classpath")
    class Hit {

        @Test
        @DisplayName("scanResource delegates to descriptor and returns its result (sentinel operationId)")
        void scanResource_usesDescriptorWhenPresent() {
            ResourceScanner scanner = new ResourceScanner(new SecurityPolicyBuilder());
            List<SecurityPolicyViolation> violations = new ArrayList<>();

            List<ResourceMethodMeta> result = scanner.scanResource(new SeamResource(), violations);

            assertEquals(1, result.size(), "Descriptor must return exactly 1 entry");
            assertEquals(
                    SeamResource_JaxRsDescriptor.SENTINEL_OPERATION_ID,
                    result.get(0).operationId(),
                    "operationId must match the descriptor sentinel — not the reflective method name 'hello'");
        }

        @Test
        @DisplayName("public scanResource(Object) overload also uses the descriptor fast-path")
        void publicScanResource_usesDescriptor() {
            ResourceScanner scanner = new ResourceScanner(new SecurityPolicyBuilder());

            List<ResourceMethodMeta> result = scanner.scanResource(new SeamResource());

            assertEquals(1, result.size());
            assertEquals(
                    SeamResource_JaxRsDescriptor.SENTINEL_OPERATION_ID,
                    result.get(0).operationId());
        }
    }

    // --- Miss: no companion ---

    /**
     * Minimal resource without a companion {@code _JaxRsDescriptor} on the test classpath.
     * The reflective walk discovers the {@code @GET} method and produces operationId {@code "get"}.
     */
    @Path("/no-companion")
    static class NoCompanionResource {

        /** Simple GET endpoint. */
        @GET
        public String get() {
            return "ok";
        }
    }

    @Nested
    @DisplayName("descriptor miss — no companion on classpath")
    class Miss {

        @Test
        @DisplayName("scanResource falls back to reflective walk when no companion exists")
        void scanResource_fallsBackToReflectionOnMiss() {
            ResourceScanner scanner = new ResourceScanner(new SecurityPolicyBuilder());
            List<SecurityPolicyViolation> violations = new ArrayList<>();

            List<ResourceMethodMeta> result = scanner.scanResource(new NoCompanionResource(), violations);

            assertEquals(1, result.size(), "Reflective walk must discover the @GET method");
            assertEquals(
                    "get",
                    result.get(0).operationId(),
                    "operationId must be the reflective method name — no descriptor override present");
        }
    }

    // --- Broken: companion present but constructor throws ---

    @Nested
    @DisplayName("broken companion — companion present but fails to instantiate")
    class Broken {

        @Test
        @DisplayName("scanResource propagates RuntimeException when companion constructor throws")
        void scanResource_propagatesExceptionFromBrokenCompanion() {
            ResourceScanner scanner = new ResourceScanner(new SecurityPolicyBuilder());
            List<SecurityPolicyViolation> violations = new ArrayList<>();

            // BrokenDescriptorResource has a companion whose constructor throws
            assertThrows(
                    RuntimeException.class,
                    () -> scanner.scanResource(new BrokenDescriptorResource(), violations),
                    "scanResource must NOT silently fall back to reflective path when a companion is broken");
        }
    }
}
