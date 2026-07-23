// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientProcessor} emits the correct error when a method does not
 * return {@code Future<T>}, and succeeds when it does.
 */
class RestClientProcessorReturnTypeTest {

    @Test
    @DisplayName("method returning String emits mustReturnFuture error")
    void nonFutureReturnType_emitsError() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    String getUser();
                                }
                                """));

        result.assertFailed().assertErrorMessage(Diagnostics.mustReturnFuture("getUser"));
    }

    @Test
    @DisplayName("method returning Future<List<User>> succeeds")
    void futureOfListReturnType_succeeds() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.User", """
                                package dev.vertique.examples.client;

                                public record User(String id, String name) {}
                                """),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;
                                import java.util.List;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    Future<List<User>> listUsers();
                                }
                                """));

        result.assertSuccess();
    }

    @Test
    @DisplayName("method returning Future<Void> succeeds")
    void futureVoidReturnType_succeeds() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.DELETE;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.PathParam;

                                @RestClient
                                public interface UserClient {
                                    @DELETE
                                    @Path("/{id}")
                                    Future<Void> deleteUser(@PathParam("id") String id);
                                }
                                """));

        result.assertSuccess();
    }
}
