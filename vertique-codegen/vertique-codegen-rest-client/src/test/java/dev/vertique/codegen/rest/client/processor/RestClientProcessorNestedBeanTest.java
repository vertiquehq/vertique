// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientProcessor} generates correctly-named {@code BeanParamAccessor}
 * classes for nested (inner / static-nested) bean types, using the
 * {@link dev.vertique.codegen.support.Identifiers#generatedClassName} scheme that flattens
 * enclosing type names with {@code _}.
 *
 * <p>For a bean type {@code Outer.Inner} the generated accessor must be named
 * {@code Outer_Inner_BeanParamAccessor} so that the runtime
 * {@link dev.vertique.rest.client.BeanParamAccessorRegistry} (which performs the same
 * {@code $} &rarr; {@code _} translation on the binary class name) can find it via
 * {@code Class.forName}.
 */
class RestClientProcessorNestedBeanTest {

    @Test
    @DisplayName("nested static bean generates accessor with enclosing-class-flattened name")
    void nestedStaticBean_generatesCorrectlyNamedAccessor() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.Requests", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public final class Requests {
                                    // Static nested bean type — binary name is Requests$PageBean
                                    public static class PageBean {
                                        @QueryParam("page") public int page;
                                        @QueryParam("size") public int size;
                                    }
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.NestedBeanClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;
                                import java.util.List;

                                @RestClient
                                public interface NestedBeanClient {
                                    @GET
                                    Future<List<String>> list(@BeanParam Requests.PageBean paging);
                                }
                                """));

        result.assertSuccess();
        // Accessor must be named Requests_PageBean_BeanParamAccessor, not PageBean_BeanParamAccessor
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.Requests_PageBean_BeanParamAccessor", "BeanParamAccessor");
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.Requests_PageBean_BeanParamAccessor", "bean.page");
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.Requests_PageBean_BeanParamAccessor", "bean.size");
    }

    @Test
    @DisplayName("nested record bean generates accessor with enclosing-class-flattened name")
    void nestedRecordBean_generatesCorrectlyNamedAccessor() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.Params", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public final class Params {
                                    public record Sort(@QueryParam("sort") String field, @QueryParam("dir") String dir) {}
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.SortedClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;
                                import java.util.List;

                                @RestClient
                                public interface SortedClient {
                                    @GET
                                    Future<List<String>> list(@BeanParam Params.Sort sort);
                                }
                                """));

        result.assertSuccess();
        // Must be Params_Sort_BeanParamAccessor
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.Params_Sort_BeanParamAccessor", "BeanParamAccessor");
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.Params_Sort_BeanParamAccessor", "bean.field()");
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.Params_Sort_BeanParamAccessor", "bean.dir()");
    }
}
