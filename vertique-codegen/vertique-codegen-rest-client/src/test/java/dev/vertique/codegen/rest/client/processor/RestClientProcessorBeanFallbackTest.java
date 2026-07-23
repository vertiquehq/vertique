// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientProcessor} skips accessor generation for beans where at least one
 * field is private with no accessible getter — the entire bean falls back to the reflective
 * accessor.
 */
class RestClientProcessorBeanFallbackTest {

    @Test
    @DisplayName("private field with no getter causes full bean-level fallback — no accessor emitted")
    void privateFieldNoGetter_noAccessorEmitted() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.SecretBean", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;
                                import jakarta.ws.rs.HeaderParam;

                                public class SecretBean {
                                    @QueryParam("page") public int page;
                                    @HeaderParam("X-Token") private String token;
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.SecretClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface SecretClient {
                                    @GET
                                    Future<String> doIt(@BeanParam SecretBean bean);
                                }
                                """));

        result.assertSuccess();
        var accessor =
                result.compilation().generatedSourceFile("dev.vertique.examples.client.SecretBean_BeanParamAccessor");
        org.junit.jupiter.api.Assertions.assertTrue(
                accessor.isEmpty(),
                "Expected no accessor for bean with inaccessible private field (full bean-level fallback)");
    }

    @Test
    @DisplayName("bean where all fields are accessible generates accessor correctly")
    void allFieldsAccessible_generatesAccessor() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.PublicBean", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;
                                import jakarta.ws.rs.HeaderParam;

                                public class PublicBean {
                                    @QueryParam("page") public int page;
                                    @HeaderParam("X-Tenant") public String tenant;
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.PublicClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface PublicClient {
                                    @GET
                                    Future<String> doIt(@BeanParam PublicBean bean);
                                }
                                """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.PublicBean_BeanParamAccessor", "BeanParamAccessor");
    }
}
