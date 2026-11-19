// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientProcessor} generates a proxy for the operations a client interface
 * inherits from generic super-interfaces: the proxy implements them with the types the interface
 * binds, keys each by the method of its declaring interface, and folds a redeclaration into one
 * operation.
 */
class RestClientProcessorInheritanceTest {

    private static final String PROXY_FQN = "dev.vertique.examples.client.UserClient_RestClientProxy";

    private static JavaFileObject[] common(JavaFileObject... sources) {
        JavaFileObject[] base = {
            RestClientProcessorFixtures.restClientAnnotation(),
            RestClientProcessorFixtures.futureClass(),
            RestClientProcessorFixtures.restClientDispatcher(),
            RestClientProcessorFixtures.restRequestBuilder(),
            RestClientProcessorFixtures.restClientException(),
            RestClientProcessorFixtures.beanParamAccessorRegistry(),
            RestClientProcessorFixtures.beanParamAccessor(),
            RestClientProcessorFixtures.clientMethodMeta(),
            SourceFiles.inline("dev.vertique.examples.client.User", """
                            package dev.vertique.examples.client;

                            public record User(String id) {}
                            """),
            SourceFiles.inline("dev.vertique.examples.client.Crud", """
                            package dev.vertique.examples.client;

                            import io.vertx.core.Future;
                            import jakarta.ws.rs.DELETE;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            public interface Crud<T, ID> {
                                @GET
                                @Path("/{id}")
                                Future<T> get(@PathParam("id") ID id);

                                @POST
                                Future<T> create(T body);

                                @DELETE
                                @Path("/{id}")
                                Future<Void> delete(@PathParam("id") ID id);
                            }
                            """)
        };
        JavaFileObject[] all = new JavaFileObject[base.length + sources.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(sources, 0, all, base.length, sources.length);
        return all;
    }

    @Test
    @DisplayName(
            "inherited generic operations are implemented with the bound types and keyed by their declaring interface")
    void inheritedOperationsAreImplemented() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(), common(SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import jakarta.ws.rs.Path;

                                @RestClient
                                @Path("/users")
                                public interface UserClient extends Crud<User, String> {}
                                """)));

        result.assertSuccess();
        result.assertGeneratedSourceContains(PROXY_FQN, "public Future<User> get(String id)");
        result.assertGeneratedSourceContains(PROXY_FQN, "public Future<User> create(User body)");
        result.assertGeneratedSourceContains(PROXY_FQN, "public Future<Void> delete(String id)");
        result.assertGeneratedSourceContains(PROXY_FQN, "Crud.class.getDeclaredMethod(\"get\", Object.class)");
        result.assertGeneratedSourceContains(PROXY_FQN, "Crud.class.getDeclaredMethod(\"delete\", Object.class)");
    }

    @Test
    @DisplayName("a redeclared operation is one proxy method that reads the declaration's annotations")
    void redeclaredOperationIsOneMethod() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(), common(SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.Path;

                                @RestClient
                                @Path("/users")
                                public interface UserClient extends Crud<User, String> {
                                    @Override
                                    Future<Void> delete(String id);
                                }
                                """)));

        result.assertSuccess();
        result.assertGeneratedSourceContains(PROXY_FQN, "UserClient.class.getDeclaredMethod(\"delete\", String.class)");
        result.assertGeneratedSourceContains(PROXY_FQN, "dispatcher.applyPathParam(");
        result.assertGeneratedSourceDoesNotContain(PROXY_FQN, "Crud.class.getDeclaredMethod(\"delete\"");
    }

    @Test
    @DisplayName("an inherited method without a verb is reported at compile time")
    void inheritedMethodWithoutVerbIsAnError() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.Base", """
                                package dev.vertique.examples.client;

                                import io.vertx.core.Future;

                                public interface Base {
                                    Future<String> ping();
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;

                                @RestClient
                                public interface UserClient extends Base {}
                                """));

        result.assertFailed().assertErrorMessage("ping is missing a supported HTTP verb annotation");
    }

    @Test
    @DisplayName("a default method that overrides an inherited abstract method is not an operation")
    void defaultMethodOverridingInheritedAbstractIsNotAnOperation() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                common(
                        SourceFiles.inline("dev.vertique.examples.client.Closeable", """
                                package dev.vertique.examples.client;

                                public interface Closeable {
                                    void close();
                                }
                                """),
                        SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;

                                @RestClient
                                @Path("/users")
                                public interface UserClient extends Closeable {
                                    @Override
                                    default void close() {}

                                    @GET
                                    Future<String> list();
                                }
                                """)));

        result.assertSuccess();
        result.assertGeneratedSourceContains(PROXY_FQN, "public Future<String> list()");
        result.assertGeneratedSourceDoesNotContain(PROXY_FQN, "close");
    }

    @Test
    @DisplayName("an abstract redeclaration of a default method reads the verb of the default declaration")
    void reabstractedDefaultMethodKeepsItsVerb() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                common(
                        SourceFiles.inline("dev.vertique.examples.client.Base", """
                                package dev.vertique.examples.client;

                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;

                                public interface Base {
                                    @GET
                                    default Future<String> ping() { return null; }
                                }
                                """),
                        SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.Path;

                                @RestClient
                                @Path("/users")
                                public interface UserClient extends Base {
                                    @Override
                                    Future<String> ping();
                                }
                                """)));

        result.assertSuccess();
        result.assertGeneratedSourceContains(PROXY_FQN, "UserClient.class.getDeclaredMethod(\"ping\")");
    }

    @Test
    @DisplayName("an operation declared by an interface the proxy cannot name leaves the reflective proxy in charge")
    void inaccessibleDeclaringInterfaceGeneratesNoProxy() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                common(
                        SourceFiles.inline("dev.vertique.examples.other.Hidden", """
                                package dev.vertique.examples.other;

                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;

                                interface Hidden<T> {
                                    @GET
                                    Future<T> get();
                                }
                                """),
                        SourceFiles.inline("dev.vertique.examples.other.Visible", """
                                package dev.vertique.examples.other;

                                import dev.vertique.examples.client.User;

                                public interface Visible extends Hidden<User> {}
                                """),
                        SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import jakarta.ws.rs.Path;

                                @RestClient
                                @Path("/users")
                                public interface UserClient extends dev.vertique.examples.other.Visible {}
                                """)));

        result.assertSuccess();
        org.junit.jupiter.api.Assertions.assertThrows(
                org.opentest4j.AssertionFailedError.class,
                () -> result.assertGeneratedSourceContains(PROXY_FQN, "UserClient"));
    }
}
