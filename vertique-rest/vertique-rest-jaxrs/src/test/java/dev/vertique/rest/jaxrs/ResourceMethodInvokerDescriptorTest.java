// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link ResourceMethodInvoker} constructor that takes the route's operation descriptor refuses
 * a missing descriptor when the invoker is built, so a route can never be served by an invoker whose
 * interceptors would see no operation.
 */
class ResourceMethodInvokerDescriptorTest {

    /** A resource with one public method for the invoker's metadata. */
    static class FixtureResource {
        public String greet() {
            return "hello";
        }
    }

    @Test
    @DisplayName("Building an invoker with a null operation descriptor fails at construction, naming the descriptor")
    void nullDescriptorIsRejectedAtConstruction() throws NoSuchMethodException {
        FixtureResource resource = new FixtureResource();
        Method method = FixtureResource.class.getMethod("greet");
        ResourceMethodMeta meta = new ResourceMethodMeta(
                resource,
                method,
                "greet",
                "GET",
                "/greet",
                List.of(),
                String.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);

        NullPointerException thrown = assertThrows(
                NullPointerException.class,
                () -> new ResourceMethodInvoker(
                        meta,
                        List.of(),
                        mock(ErrorPipeline.class),
                        mock(ResponsePipeline.class),
                        new RestContextResolution(Set.of()),
                        List.of(),
                        null,
                        null,
                        null,
                        ConversionContexts.defaultResolver(),
                        JacksonFieldNameResolver.forRoute(null),
                        null),
                "a null descriptor must be refused when the invoker is built");

        assertEquals("descriptor", thrown.getMessage(), "the refusal must name the missing argument");
    }
}
