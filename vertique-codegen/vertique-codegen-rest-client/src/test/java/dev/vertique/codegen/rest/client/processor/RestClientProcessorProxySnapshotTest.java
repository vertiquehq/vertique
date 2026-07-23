// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Snapshot-style tests that verify the generated proxy class content for a multi-method interface
 * including overloaded methods, confirming correct method-keyed metadata and import structure.
 */
class RestClientProcessorProxySnapshotTest {

    private static final String PROXY_FQN = "dev.vertique.examples.client.UserClient_RestClientProxy";

    @Test
    @DisplayName("generated proxy implements the client interface")
    void generatedProxy_implementsInterface() {
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
                                import jakarta.ws.rs.QueryParam;

                                @RestClient
                                @Path("/users")
                                public interface UserClient {
                                    @GET
                                    Future<String> list(@QueryParam("page") int page);

                                    @GET
                                    @Path("/{id}")
                                    Future<String> findById(@PathParam("id") String id);
                                }
                                """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(PROXY_FQN, "implements UserClient");
    }

    @Test
    @DisplayName("generated proxy has static Method constants in class initializer")
    void generatedProxy_hasStaticMethodConstants() {
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
                                    Future<String> findById(@PathParam("id") String id);
                                }
                                """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(PROXY_FQN, "getDeclaredMethod");
        result.assertGeneratedSourceContains(PROXY_FQN, "ExceptionInInitializerError");
    }

    @Test
    @DisplayName("generated proxy has @Generated annotation")
    void generatedProxy_hasGeneratedAnnotation() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    Future<String> get();
                                }
                                """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(PROXY_FQN, "@Generated");
        result.assertGeneratedSourceContains(PROXY_FQN, "RestClientProcessor");
    }

    @Test
    @DisplayName("overloaded methods produce distinct method constants — proxy compiles")
    void overloadedMethods_distinctConstants() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.OverloadClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.QueryParam;

                                @RestClient
                                public interface OverloadClient {
                                    @GET
                                    Future<String> find(@QueryParam("id") String id);

                                    @GET
                                    Future<String> find(@QueryParam("id") String id, @QueryParam("v") int version);
                                }
                                """));

        result.assertSuccess();
        // Two distinct static Method fields should be emitted
        var fqn = "dev.vertique.examples.client.OverloadClient_RestClientProxy";
        result.assertGeneratedSourceContains(fqn, "implements OverloadClient");
        // Both methods produce distinct M_ constants (different suffix index)
        result.assertGeneratedSourceContains(fqn, "M_FIND_0");
        result.assertGeneratedSourceContains(fqn, "M_FIND_1");
    }

    @Test
    @DisplayName("generated proxy has constructor accepting dispatcher, registry, and methodMetas")
    void generatedProxy_hasExpectedConstructor() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    Future<String> get();
                                }
                                """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(PROXY_FQN, "RestClientDispatcher");
        result.assertGeneratedSourceContains(PROXY_FQN, "BeanParamAccessorRegistry");
        result.assertGeneratedSourceContains(PROXY_FQN, "Map");
    }
}
