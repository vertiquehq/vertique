// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientProcessor} correctly classifies {@code @Url}-annotated parameters
 * as {@link dev.vertique.codegen.rest.client.processor.ParamModel.Kind#URL} and validates the
 * constraints enforced by the APT scanner.
 *
 * <p>Happy-path tests verify proxy emission; error-path tests verify that compile-time diagnostics
 * are emitted for each constraint violation (mirroring the runtime scanner's
 * {@code IllegalArgumentException} checks).
 */
class RestClientProcessorUrlParamTest {

    // --- Happy-path ---

    @Nested
    @DisplayName("Happy path — valid @Url usage")
    class HappyPath {

        @Test
        @DisplayName("@Url parameter emits null-guarded absoluteUri call in generated proxy")
        void urlParam_emitsAbsoluteUriCall() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.restClientAnnotation(),
                    RestClientProcessorFixtures.futureClass(),
                    RestClientProcessorFixtures.restClientDispatcher(),
                    RestClientProcessorFixtures.restRequestBuilder(),
                    RestClientProcessorFixtures.restClientException(),
                    RestClientProcessorFixtures.beanParamAccessorRegistry(),
                    RestClientProcessorFixtures.beanParamAccessor(),
                    RestClientProcessorFixtures.clientMethodMeta(),
                    RestClientProcessorFixtures.urlAnnotation(),
                    SourceFiles.inline("dev.vertique.examples.client.WebhookClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.POST;
                                    import java.net.URI;

                                    @RestClient
                                    public interface WebhookClient {
                                        @POST
                                        Future<String> deliver(@Url URI url, String payload);
                                    }
                                    """));

            result.assertSuccess();
            var proxyFqn = "dev.vertique.examples.client.WebhookClient_RestClientProxy";
            // Must route through dispatcher.applyUrlParam, passing the raw (possibly-null) value
            result.assertGeneratedSourceContains(proxyFqn, "dispatcher.applyUrlParam(_req_, ");
            result.assertGeneratedSourceContains(proxyFqn, "url)");
            // The String payload has no annotation so it becomes the body, via dispatcher.applyBody
            result.assertGeneratedSourceContains(proxyFqn, "dispatcher.applyBody(_req_, ");
        }

        @Test
        @DisplayName("@Url parameter is not treated as request body")
        void urlParam_notClassifiedAsBody() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.restClientAnnotation(),
                    RestClientProcessorFixtures.futureClass(),
                    RestClientProcessorFixtures.restClientDispatcher(),
                    RestClientProcessorFixtures.restRequestBuilder(),
                    RestClientProcessorFixtures.restClientException(),
                    RestClientProcessorFixtures.beanParamAccessorRegistry(),
                    RestClientProcessorFixtures.beanParamAccessor(),
                    RestClientProcessorFixtures.clientMethodMeta(),
                    RestClientProcessorFixtures.urlAnnotation(),
                    SourceFiles.inline("dev.vertique.examples.client.DynamicClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;
                                    import java.net.URI;

                                    @RestClient
                                    public interface DynamicClient {
                                        @GET
                                        Future<String> get(@Url URI endpoint);
                                    }
                                    """));

            result.assertSuccess();
            var proxyFqn = "dev.vertique.examples.client.DynamicClient_RestClientProxy";
            result.assertGeneratedSourceContains(proxyFqn, "dispatcher.applyUrlParam(_req_, ");
            // No bodyObject call expected for a @Url-only method
            var src = result.compilation()
                    .generatedSourceFile(proxyFqn)
                    .map(f -> {
                        try {
                            return f.getCharContent(true).toString();
                        } catch (Exception e) {
                            return "";
                        }
                    })
                    .orElse("");
            org.junit.jupiter.api.Assertions.assertFalse(
                    src.contains("bodyObject(endpoint)"),
                    "Expected @Url param NOT to be emitted as bodyObject, but found bodyObject(endpoint) in: " + src);
        }

        @Test
        @DisplayName("@Url + @QueryParam produces absoluteUri call AND a query call")
        void urlWithQueryParam_emitsBothAbsoluteUriAndQuery() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            RestClientProcessorFixtures.urlAnnotation(),
                            SourceFiles.inline("dev.vertique.examples.client.SearchClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.QueryParam;
                                    import java.net.URI;

                                    @RestClient
                                    public interface SearchClient {
                                        @GET
                                        Future<String> search(@Url URI base, @QueryParam("q") String q);
                                    }
                                    """)));

            result.assertSuccess();
            var proxyFqn = "dev.vertique.examples.client.SearchClient_RestClientProxy";
            // @Url → dispatcher.applyUrlParam; @QueryParam → dispatcher.applyQueryParam
            result.assertGeneratedSourceContains(proxyFqn, "applyUrlParam");
            result.assertGeneratedSourceContains(proxyFqn, "applyQueryParam");
        }
    }

    // --- Error-path: compile-time constraint violations ---

    @Nested
    @DisplayName("Error path — APT validation of @Url constraints")
    class ErrorPath {

        @Test
        @DisplayName("@Url on non-URI type emits a compile error")
        void urlOnNonUriType_emitsError() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            RestClientProcessorFixtures.urlAnnotation(),
                            SourceFiles.inline("dev.vertique.examples.client.BadTypeClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;

                                    @RestClient
                                    public interface BadTypeClient {
                                        @GET
                                        Future<String> get(@Url String url);
                                    }
                                    """)));

            result.assertFailed().assertErrorMessage("@Url parameter must be java.net.URI");
        }

        @Test
        @DisplayName("@Url + @DefaultValue emits a compile error")
        void urlWithDefaultValue_emitsError() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            RestClientProcessorFixtures.urlAnnotation(),
                            SourceFiles.inline("dev.vertique.examples.client.DefaultUrlClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.DefaultValue;
                                    import jakarta.ws.rs.GET;
                                    import java.net.URI;

                                    @RestClient
                                    public interface DefaultUrlClient {
                                        @GET
                                        Future<String> get(@Url @DefaultValue("http://example.com") URI url);
                                    }
                                    """)));

            result.assertFailed().assertErrorMessage("@DefaultValue is not allowed on @Url parameters");
        }

        @Test
        @DisplayName("@Url + @QueryParam on the same parameter emits a compile error")
        void urlWithQueryParam_onSameParam_emitsError() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            RestClientProcessorFixtures.urlAnnotation(),
                            SourceFiles.inline("dev.vertique.examples.client.ConflictClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.QueryParam;
                                    import java.net.URI;

                                    @RestClient
                                    public interface ConflictClient {
                                        @GET
                                        Future<String> get(@Url @QueryParam("q") URI url);
                                    }
                                    """)));

            result.assertFailed().assertErrorMessage("mutually exclusive with @QueryParam");
        }

        @Test
        @DisplayName("Two @Url parameters on one method emits a compile error")
        void twoUrlParams_emitsError() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            RestClientProcessorFixtures.urlAnnotation(),
                            SourceFiles.inline("dev.vertique.examples.client.DoubleUrlClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;
                                    import java.net.URI;

                                    @RestClient
                                    public interface DoubleUrlClient {
                                        @GET
                                        Future<String> get(@Url URI a, @Url URI b);
                                    }
                                    """)));

            result.assertFailed().assertErrorMessage("At most one @Url parameter is allowed");
        }

        @Test
        @DisplayName("@Url method with method-level @Path emits a compile error")
        void urlMethodWithMethodPath_emitsError() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            RestClientProcessorFixtures.urlAnnotation(),
                            SourceFiles.inline("dev.vertique.examples.client.PathUrlClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.Path;
                                    import java.net.URI;

                                    @RestClient
                                    public interface PathUrlClient {
                                        @GET
                                        @Path("/items")
                                        Future<String> get(@Url URI url);
                                    }
                                    """)));

            result.assertFailed().assertErrorMessage("must not have method-level @Path");
        }

        @Test
        @DisplayName("@Url method with interface-level @Path emits a compile error")
        void urlMethodWithClassPath_emitsError() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            RestClientProcessorFixtures.urlAnnotation(),
                            SourceFiles.inline("dev.vertique.examples.client.ClassPathUrlClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.Path;
                                    import java.net.URI;

                                    @RestClient
                                    @Path("/api")
                                    public interface ClassPathUrlClient {
                                        @GET
                                        Future<String> get(@Url URI url);
                                    }
                                    """)));

            result.assertFailed().assertErrorMessage("must not have interface-level @Path");
        }

        @Test
        @DisplayName("@Url method with @PathParam emits a compile error")
        void urlMethodWithPathParam_emitsError() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            RestClientProcessorFixtures.urlAnnotation(),
                            SourceFiles.inline("dev.vertique.examples.client.PathParamUrlClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.PathParam;
                                    import java.net.URI;

                                    @RestClient
                                    public interface PathParamUrlClient {
                                        @GET
                                        Future<String> get(@Url URI url, @PathParam("id") String id);
                                    }
                                    """)));

            result.assertFailed().assertErrorMessage("must not have @PathParam parameters");
        }

        @Test
        @DisplayName("@Url method with @PathParam inside @BeanParam emits a compile error at APT time")
        void urlMethodWithPathParamInBeanParam_emitsError() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(
                            RestClientProcessorFixtures.urlAnnotation(),
                            SourceFiles.inline("dev.vertique.examples.client.PathFilter", """
                                    package dev.vertique.examples.client;

                                    import jakarta.ws.rs.BeanParam;
                                    import jakarta.ws.rs.PathParam;

                                    public class PathFilter {
                                        @PathParam("id")
                                        public String id;
                                    }
                                    """),
                            SourceFiles.inline("dev.vertique.examples.client.BeanPathParamUrlClient", """
                                    package dev.vertique.examples.client;

                                    import dev.vertique.rest.client.RestClient;
                                    import dev.vertique.rest.client.Url;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.BeanParam;
                                    import jakarta.ws.rs.GET;
                                    import java.net.URI;

                                    @RestClient
                                    public interface BeanPathParamUrlClient {
                                        @GET
                                        Future<String> get(@Url URI url, @BeanParam PathFilter filter);
                                    }
                                    """)));

            result.assertFailed().assertErrorMessage("must not have @PathParam parameters");
        }
    }
}
