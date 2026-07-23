// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that when two {@code @RestClient} interfaces reference the same {@code @BeanParam} type,
 * exactly ONE accessor is emitted — not two.
 */
class RestClientProcessorBeanDedupTest {

    @Test
    @DisplayName("two @RestClient interfaces sharing a bean type emit only one accessor")
    void sharedBeanType_emitsOneAccessor() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.PageRequest", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public record PageRequest(@QueryParam("page") int page) {}
                                """),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;
                                import java.util.List;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    Future<List<String>> list(@BeanParam PageRequest paging);
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.AdminClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;
                                import java.util.List;

                                @RestClient
                                public interface AdminClient {
                                    @GET
                                    Future<List<String>> adminList(@BeanParam PageRequest paging);
                                }
                                """));

        result.assertSuccess();

        // The accessor should be generated exactly once
        var accessor =
                result.compilation().generatedSourceFile("dev.vertique.examples.client.PageRequest_BeanParamAccessor");
        org.junit.jupiter.api.Assertions.assertTrue(
                accessor.isPresent(), "Expected exactly one accessor to be generated");

        // Count how many times the accessor class appears in generated sources
        long accessorCount = result.compilation().generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("PageRequest_BeanParamAccessor"))
                .count();
        assertEquals(1L, accessorCount, "Expected exactly one PageRequest_BeanParamAccessor generated file");
    }
}
