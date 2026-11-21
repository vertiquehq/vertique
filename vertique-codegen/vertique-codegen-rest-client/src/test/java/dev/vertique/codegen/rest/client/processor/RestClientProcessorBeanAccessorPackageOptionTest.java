// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that the {@code -Avertique.codegen.package} option, which is ignored for placement of
 * {@code {Bean}_BeanParamAccessor} classes, also has no effect on how the scanner decides whether
 * the accessor may read a field directly. The accessor always lives in the bean's own package, so
 * direct access is allowed exactly for fields visible from that package.
 */
class RestClientProcessorBeanAccessorPackageOptionTest {

    private static final Map<String, String> OVERRIDE = Map.of("vertique.codegen.package", "com.override.pkg");

    @Test
    @DisplayName("package-private field in the bean's own package is read directly although the option is set")
    void packagePrivateFieldInBeanPackage_directAccessWithOptionSet() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                OVERRIDE,
                RestClientProcessorFixtures.allStubsWith(
                        SourceFiles.inline("com.example.PagedBean", """
                                package com.example;

                                import jakarta.ws.rs.QueryParam;

                                public class PagedBean {
                                    @QueryParam("page") int page;
                                }
                                """),
                        SourceFiles.inline("com.example.PagedClient", """
                                package com.example;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface PagedClient {
                                    @GET
                                    Future<String> get(@BeanParam PagedBean bean);
                                }
                                """)));

        result.assertSuccess();
        // The accessor sits in com.example, so the package-private field is visible to it. Treating
        // the override package as the accessor's package drops the accessor (no getter exists).
        result.assertGeneratedSourceContains("com.example.PagedBean_BeanParamAccessor", "bean.page");
    }

    @Test
    @DisplayName(
            "package-private superclass field in the override package is not read directly from the bean's package")
    void superclassFieldInOverridePackage_noInaccessibleDirectAccess() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                OVERRIDE,
                RestClientProcessorFixtures.allStubsWith(
                        SourceFiles.inline("com.override.pkg.BaseBean", """
                                package com.override.pkg;

                                import jakarta.ws.rs.QueryParam;

                                public class BaseBean {
                                    @QueryParam("page") int page;
                                }
                                """),
                        SourceFiles.inline("com.example.PagedBean", """
                                package com.example;

                                import com.override.pkg.BaseBean;

                                public class PagedBean extends BaseBean {}
                                """),
                        SourceFiles.inline("com.example.PagedClient", """
                                package com.example;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface PagedClient {
                                    @GET
                                    Future<String> get(@BeanParam PagedBean bean);
                                }
                                """)));

        // The field is package-private in another package and has no getter, so the bean must fall
        // back to the reflective accessor. Emitting "bean.page" from com.example does not compile.
        result.assertSuccess();
        assertTrue(
                result.compilation()
                        .generatedSourceFile("com.example.PagedBean_BeanParamAccessor")
                        .isEmpty(),
                "Expected no accessor: the inherited field is not visible from the bean's package");
    }
}
