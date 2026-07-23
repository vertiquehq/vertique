// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientProcessor} validates HTTP verb presence on non-default methods and
 * rejects {@code @OPTIONS} (which the runtime scanner also does not support).
 */
class RestClientProcessorHttpVerbTest {

    @Test
    @DisplayName("non-default method without HTTP verb emits error")
    void missingHttpVerb_emitsError() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;

                                @RestClient
                                public interface UserClient {
                                    Future<String> getUser();
                                }
                                """));

        result.assertFailed().assertErrorMessage("is missing a supported HTTP verb annotation");
    }

    @Test
    @DisplayName("default method without HTTP verb does not emit error")
    void defaultMethodWithoutVerb_noError() {
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
                                    Future<String> getUser();

                                    default String helper() { return "ok"; }
                                }
                                """));

        result.assertSuccess();
    }

    @Test
    @DisplayName("@OPTIONS is rejected — not supported by the runtime scanner")
    void optionsVerb_isRejected() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("jakarta.ws.rs.OPTIONS", """
                                package jakarta.ws.rs;

                                import java.lang.annotation.*;

                                @Target({ElementType.METHOD})
                                @Retention(RetentionPolicy.RUNTIME)
                                @HttpMethod("OPTIONS")
                                @Documented
                                public @interface OPTIONS {}
                                """),
                SourceFiles.inline("jakarta.ws.rs.HttpMethod", """
                                package jakarta.ws.rs;

                                import java.lang.annotation.*;

                                @Target({ElementType.ANNOTATION_TYPE})
                                @Retention(RetentionPolicy.RUNTIME)
                                @Documented
                                public @interface HttpMethod { String value(); }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.OPTIONS;

                                @RestClient
                                public interface UserClient {
                                    @OPTIONS
                                    Future<String> options();
                                }
                                """));

        result.assertFailed().assertErrorMessage("is missing a supported HTTP verb annotation");
    }

    @Test
    @DisplayName("each supported verb compiles successfully")
    void allSupportedVerbs_succeed() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.*;

                                @RestClient
                                public interface UserClient {
                                    @GET Future<String> get();
                                    @POST Future<String> post();
                                    @PUT Future<String> put();
                                    @DELETE Future<Void> delete();
                                    @PATCH Future<String> patch();
                                    @HEAD Future<Void> head();
                                }
                                """));

        result.assertSuccess();
    }
}
