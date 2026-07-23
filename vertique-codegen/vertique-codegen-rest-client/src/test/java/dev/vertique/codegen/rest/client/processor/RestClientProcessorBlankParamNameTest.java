// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the codegen-vs-runtime parity invariant for blank JAX-RS param annotation values.
 *
 * <p>JAX-RS 4.0.0 has no "blank value means use Java identifier" convention for any of
 * {@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam}, or {@code @CookieParam} — that is
 * a Spring {@code @RequestParam} behavior. The runtime client and runtime server both preserve the
 * literal annotation value end-to-end. Codegen must do the same so generated proxies and the
 * reflective runtime emit identical wire requests for the same source.
 *
 * <p>These tests guard against accidentally re-introducing a blank-fallback in either
 * {@link dev.vertique.codegen.rest.client.processor.scan.ClientInterfaceScanner} or
 * {@link dev.vertique.codegen.rest.client.processor.scan.BeanParamScanner}.
 */
class RestClientProcessorBlankParamNameTest {

    @Test
    @DisplayName("blank @PathParam(\"\") on direct param surfaces alignment error (literal preserved)")
    void blankPathParamDirect_emitsAlignmentError() {
        // If codegen falls back to the Java identifier, this would compile cleanly because the
        // synthesized name "id" matches the placeholder. Literal preservation lets
        // PathPlaceholderValidator surface the malformed annotation.
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
                                    Future<String> getUser(@PathParam("") String id);
                                }
                                """));

        result.assertFailed().assertErrorMessage(Diagnostics.pathPlaceholderMissingParam("id", "getUser"));
    }

    @Test
    @DisplayName("blank @PathParam(\"\") on @BeanParam field surfaces alignment error (literal preserved)")
    void blankPathParamBeanField_emitsAlignmentError() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.allStubsWith(
                        SourceFiles.inline("dev.vertique.examples.client.IdBean", """
                                        package dev.vertique.examples.client;

                                        import jakarta.ws.rs.PathParam;

                                        public class IdBean {
                                            @PathParam("") public String id;
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
                                            Future<String> get(@BeanParam IdBean req);
                                        }
                                        """)));

        result.assertFailed().assertErrorMessage(Diagnostics.pathPlaceholderMissingParam("id", "get"));
    }

    @Test
    @DisplayName("blank @QueryParam(\"\") on direct param emits a literal empty wire name")
    void blankQueryParamDirect_preservesLiteral() {
        // Generated proxy must contain a queryParam binding with the literal "" (not the Java
        // identifier "page"), matching what the reflective runtime client would emit.
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
                                    Future<String> list(@QueryParam("") int page);
                                }
                                """));

        result.assertSuccess();
        // The generated proxy must NOT rewrite the blank value to the Java name "page".
        result.assertGeneratedSourceContains("dev.vertique.examples.client.UserClient_RestClientProxy", "\"\"");
    }

    @Test
    @DisplayName("blank @HeaderParam(\"\") on direct param emits a literal empty wire name")
    void blankHeaderParamDirect_preservesLiteral() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.HeaderParam;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    Future<String> list(@HeaderParam("") String trace);
                                }
                                """));

        result.assertSuccess();
        result.assertGeneratedSourceContains("dev.vertique.examples.client.UserClient_RestClientProxy", "\"\"");
    }

    @Test
    @DisplayName("blank @CookieParam(\"\") on direct param emits a literal empty wire name")
    void blankCookieParamDirect_preservesLiteral() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.CookieParam;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    Future<String> list(@CookieParam("") String session);
                                }
                                """));

        result.assertSuccess();
        result.assertGeneratedSourceContains("dev.vertique.examples.client.UserClient_RestClientProxy", "\"\"");
    }
}
