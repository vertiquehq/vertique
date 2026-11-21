// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A type the generated companion's package cannot name would make the generated source fail to
 * compile far from the cause. Unlike the registration modules, which skip such a binding with a
 * warning, the REST-client proxy and {@code BeanParamAccessor} emitters refuse it with a compile error
 * that names the type.
 */
class RestClientProcessorUnnameableTypeTest {

    @Test
    @DisplayName("a private nested @BeanParam type is refused with an error naming the type")
    void privateNestedBeanParam_isRefusedWithAnErrorNamingTheType() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.allStubsWith(SourceFiles.inline("dev.vertique.examples.client.Api", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.QueryParam;

                                public final class Api {
                                    private static final class Paging {
                                        @QueryParam("page") public int page;
                                    }

                                    @RestClient
                                    public interface Users {
                                        @GET
                                        Future<String> list(@BeanParam Paging paging);
                                    }
                                }
                                """)));

        result.assertFailed().assertErrorMessage("dev.vertique.examples.client.Api.Paging");
    }

    @Test
    @DisplayName("a private nested body type is refused with an error naming the type")
    void privateNestedBodyType_isRefusedWithAnErrorNamingTheType() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.allStubsWith(SourceFiles.inline("dev.vertique.examples.client.Api", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.GET;

                                public final class Api {
                                    private record Payload(String name) {}

                                    @RestClient
                                    public interface Users {
                                        @GET
                                        Future<Payload> get();
                                    }
                                }
                                """)));

        result.assertFailed().assertErrorMessage("dev.vertique.examples.client.Api.Payload");
    }
}
