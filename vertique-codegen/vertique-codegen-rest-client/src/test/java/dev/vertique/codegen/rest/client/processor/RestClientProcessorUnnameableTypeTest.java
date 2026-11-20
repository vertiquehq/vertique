// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A type the generated companion's package cannot name must not make the generated source fail to
 * compile: the generated registration modules skip such a binding with a warning, and the REST-client
 * proxy and {@code BeanParamAccessor} emitters must either skip it the same way or leave the build intact.
 */
class RestClientProcessorUnnameableTypeTest {

    @Test
    @DisplayName("a private nested @BeanParam type does not make the generated proxy or accessor fail to compile")
    void privateNestedBeanParam_doesNotBreakTheCompilation() {
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

        result.assertSuccess();
    }
}
