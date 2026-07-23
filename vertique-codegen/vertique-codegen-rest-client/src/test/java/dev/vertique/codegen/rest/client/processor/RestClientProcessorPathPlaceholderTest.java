// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientProcessor} validates {@code @Path} placeholder consistency,
 * emitting errors for mismatches in both directions, including path params contributed via
 * {@code @BeanParam} bean types.
 */
class RestClientProcessorPathPlaceholderTest {

    @Test
    @DisplayName("@Path placeholder without @PathParam emits error")
    void placeholderWithoutPathParam_emitsError() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;

                                @RestClient
                                @Path("/users")
                                public interface UserClient {
                                    @GET
                                    @Path("/{id}")
                                    Future<String> getUser();
                                }
                                """));

        result.assertFailed().assertErrorMessage(Diagnostics.pathPlaceholderMissingParam("id", "getUser"));
    }

    @Test
    @DisplayName("@PathParam without @Path placeholder emits error")
    void pathParamWithoutPlaceholder_emitsError() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.PathParam;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    Future<String> getUser(@PathParam("id") String id);
                                }
                                """));

        result.assertFailed().assertErrorMessage(Diagnostics.pathParamMissingPlaceholder("id", "getUser"));
    }

    @Test
    @DisplayName("matching @Path placeholder and @PathParam succeeds")
    void matchingPlaceholderAndPathParam_succeeds() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.PathParam;

                                @RestClient
                                @Path("/users")
                                public interface UserClient {
                                    @GET
                                    @Path("/{id}")
                                    Future<String> getUser(@PathParam("id") String id);
                                }
                                """));

        result.assertSuccess();
    }

    @Test
    @DisplayName("@PathParam in @BeanParam bean field satisfies @Path placeholder")
    void beanParamPathField_satisfiesPlaceholder() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.allStubsWith(
                        SourceFiles.inline("dev.vertique.examples.client.IdPlusPaging", """
                                        package dev.vertique.examples.client;

                                        import jakarta.ws.rs.PathParam;
                                        import jakarta.ws.rs.QueryParam;

                                        public class IdPlusPaging {
                                            @PathParam("id") public String id;
                                            @QueryParam("page") public int page;
                                        }
                                        """),
                        SourceFiles.inline("dev.vertique.examples.client.ResourceClient", """
                                        package dev.vertique.examples.client;

                                        import dev.vertique.rest.client.RestClient;
                                        import io.vertx.core.Future;
                                        import jakarta.ws.rs.BeanParam;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;

                                        @RestClient
                                        @Path("/resources")
                                        public interface ResourceClient {
                                            @GET
                                            @Path("/{id}")
                                            Future<String> get(@BeanParam IdPlusPaging req);
                                        }
                                        """)));

        // Placeholder {id} is satisfied by the @PathParam("id") field inside the @BeanParam — must succeed
        result.assertSuccess();
    }

    @Test
    @DisplayName("no @Path and no @PathParam succeeds")
    void noPlaceholderNoPathParam_succeeds() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.QueryParam;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    Future<String> listUsers(@QueryParam("page") int page);
                                }
                                """));

        result.assertSuccess();
    }
}
