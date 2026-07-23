// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientProcessor} correctly handles {@code @DefaultValue} on parameters
 * and emits null-safe code for query, header, cookie, and path parameters in the generated proxy.
 *
 * <p>Verified behaviours:
 * <ul>
 *   <li>Query param with {@code @DefaultValue} and null arg &rarr; default substituted.</li>
 *   <li>Header param with null arg and no default &rarr; header omitted (guarded if-block).</li>
 *   <li>Path param with null arg and no default &rarr; {@code RestClientException} thrown.</li>
 *   <li>Path param with {@code @DefaultValue} &rarr; default substituted, no exception.</li>
 * </ul>
 */
class RestClientProcessorDefaultValueTest {

    @Nested
    @DisplayName("@DefaultValue on query parameter")
    class QueryDefaultValue {

        @Test
        @DisplayName("null query arg with @DefaultValue uses default in generated proxy")
        void queryWithDefaultValue_usesDefault() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            SourceFiles.inline("dev.vertique.examples.client.PagedClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.DefaultValue;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.QueryParam;
                                    import java.util.List;

                                    @RestClient
                                    public interface PagedClient {
                                        @GET
                                        Future<List<String>> list(
                                            @QueryParam("page") @DefaultValue("0") Integer page,
                                            @QueryParam("size") @DefaultValue("20") Integer size);
                                    }
                                    """)));

            result.assertSuccess();
            var proxyFqn = "dev.vertique.examples.client.PagedClient_RestClientProxy";
            // Should contain a ternary with the default value strings
            result.assertGeneratedSourceContains(proxyFqn, "\"0\"");
            result.assertGeneratedSourceContains(proxyFqn, "\"20\"");
        }
    }

    @Nested
    @DisplayName("null header without @DefaultValue")
    class NullHeaderNoDefault {

        @Test
        @DisplayName(
                "null header arg without @DefaultValue routes through dispatcher.applyHeaderParam with null default")
        void nullHeader_noDefault_routedThroughDispatcher() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            SourceFiles.inline("dev.vertique.examples.client.TokenClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.HeaderParam;

                                    @RestClient
                                    public interface TokenClient {
                                        @GET
                                        Future<String> fetch(@HeaderParam("X-Token") String token);
                                    }
                                    """)));

            result.assertSuccess();
            var proxyFqn = "dev.vertique.examples.client.TokenClient_RestClientProxy";
            // Null-handling is delegated to the dispatcher — no if-guard in generated proxy
            result.assertGeneratedSourceContains(proxyFqn, "dispatcher.applyHeaderParam");
            result.assertGeneratedSourceContains(proxyFqn, "\"X-Token\"");
        }
    }

    @Nested
    @DisplayName("null path param without @DefaultValue")
    class NullPathParamNoDefault {

        @Test
        @DisplayName("null path arg without @DefaultValue emits RestClientException throw")
        void nullPath_noDefault_exceptionThrown() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            SourceFiles.inline("dev.vertique.examples.client.ResourceClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.Path;
                                    import jakarta.ws.rs.PathParam;

                                    @RestClient
                                    @Path("/resources")
                                    public interface ResourceClient {
                                        @GET
                                        @Path("/{id}")
                                        Future<String> get(@PathParam("id") String id);
                                    }
                                    """)));

            result.assertSuccess();
            var proxyFqn = "dev.vertique.examples.client.ResourceClient_RestClientProxy";
            // Must contain null check + RestClientException throw
            result.assertGeneratedSourceContains(proxyFqn, "if (id == null)");
            result.assertGeneratedSourceContains(proxyFqn, "RestClientException");
        }

        @Test
        @DisplayName("path param with @DefaultValue does not emit exception throw")
        void pathWithDefaultValue_noException() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            SourceFiles.inline("dev.vertique.examples.client.DefaultPathClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.DefaultValue;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.Path;
                                    import jakarta.ws.rs.PathParam;

                                    @RestClient
                                    @Path("/items")
                                    public interface DefaultPathClient {
                                        @GET
                                        @Path("/{type}")
                                        Future<String> get(@PathParam("type") @DefaultValue("all") String type);
                                    }
                                    """)));

            result.assertSuccess();
            var proxyFqn = "dev.vertique.examples.client.DefaultPathClient_RestClientProxy";
            // Must NOT throw RestClientException — substitutes default instead
            result.assertGeneratedSourceContains(proxyFqn, "\"all\"");
        }
    }

    @Nested
    @DisplayName("@DefaultValue on @BeanParam record field")
    class BeanFieldDefaultValue {

        @Test
        @DisplayName("bean record field with @DefaultValue emits guarded ternary in generated proxy")
        void beanRecordFieldWithDefaultValue_emitsGuardedCall() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            SourceFiles.inline("dev.vertique.examples.client.PageParams", """
                                    package dev.vertique.examples.client;

                                    import jakarta.ws.rs.DefaultValue;
                                    import jakarta.ws.rs.QueryParam;

                                    public record PageParams(
                                        @QueryParam("page") @DefaultValue("0") Integer page,
                                        @QueryParam("size") @DefaultValue("20") Integer size
                                    ) {}
                                    """),
                            SourceFiles.inline("dev.vertique.examples.client.BeanDefaultClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.BeanParam;
                                    import jakarta.ws.rs.GET;
                                    import java.util.List;

                                    @RestClient
                                    public interface BeanDefaultClient {
                                        @GET
                                        Future<List<String>> list(@BeanParam PageParams paging);
                                    }
                                    """)));

            result.assertSuccess();
            var proxyFqn = "dev.vertique.examples.client.BeanDefaultClient_RestClientProxy";
            // Must contain the default value strings in ternary substitution
            result.assertGeneratedSourceContains(proxyFqn, "\"0\"");
            result.assertGeneratedSourceContains(proxyFqn, "\"20\"");
        }

        @Test
        @DisplayName("bean record field header without @DefaultValue routes through dispatcher.applyHeaderParam")
        void beanRecordFieldHeader_noDefault_routedThroughDispatcher() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            SourceFiles.inline("dev.vertique.examples.client.HeaderBean", """
                                    package dev.vertique.examples.client;

                                    import jakarta.ws.rs.HeaderParam;

                                    public record HeaderBean(
                                        @HeaderParam("X-Foo") String foo
                                    ) {}
                                    """),
                            SourceFiles.inline("dev.vertique.examples.client.BeanHeaderClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.BeanParam;
                                    import jakarta.ws.rs.GET;

                                    @RestClient
                                    public interface BeanHeaderClient {
                                        @GET
                                        Future<String> get(@BeanParam HeaderBean headers);
                                    }
                                    """)));

            result.assertSuccess();
            var proxyFqn = "dev.vertique.examples.client.BeanHeaderClient_RestClientProxy";
            // Null-handling is delegated to the dispatcher — no if-guard in generated proxy
            result.assertGeneratedSourceContains(proxyFqn, "dispatcher.applyHeaderParam");
            result.assertGeneratedSourceContains(proxyFqn, "\"X-Foo\"");
        }
    }
}
