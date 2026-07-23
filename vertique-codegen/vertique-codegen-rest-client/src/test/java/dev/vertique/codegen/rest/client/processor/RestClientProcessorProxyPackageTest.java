// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests that verify the generated {@code {Client}_RestClientProxy} always lands in the
 * <em>origin</em> package of the annotated interface, regardless of whether the
 * {@code -Avertique.codegen.package} override is set.
 *
 * <p>The proxy FQN is constructed at runtime by
 * {@link dev.vertique.rest.client.RestClientBuilder} using
 * {@link dev.vertique.core.util.GeneratedNames#companionFqn}, which always uses the
 * origin package with {@code $}-to-{@code _} flattening. The emitter must therefore also
 * pin to the origin package (via {@link dev.vertique.codegen.CodegenContext#packageNameOf})
 * so the runtime lookup finds the class. Aggregate Dagger-module generation (a separate
 * output) may still honor the {@code -A} override; the proxy must not.
 *
 * <p>Nested-interface flattening is verified in {@link Nested}: the processor must emit
 * {@code Outer_Inner_RestClientProxy} (joined with {@code _}), not {@code Inner_RestClientProxy}
 * (which uses only {@code getSimpleName()} without walking the enclosing chain).
 */
class RestClientProcessorProxyPackageTest {

    // --- -A override isolation ---

    @Nested
    @DisplayName("package override (-Avertique.codegen.package) does not affect proxy package")
    class PackageOverrideIsolation {

        @Test
        @DisplayName("proxy lands in origin package, not the override package")
        void proxyLandsInOriginPackage_notOverride() {
            // The proxy must be at com.example.Foo_RestClientProxy, NOT at com.acme.gen.Foo_RestClientProxy.
            // If the emitter incorrectly uses ctx.outputPackage() (which honours the -A override),
            // the proxy would be emitted into com.acme.gen, where GeneratedNames.companionFqn()
            // (used by RestClientBuilder) would never look for it.
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    Map.of("vertique.codegen.package", "com.acme.gen"),
                    RestClientProcessorFixtures.allStubsWith(SourceFiles.inline("com.example.Foo", """
                            package com.example;

                            import dev.vertique.rest.client.RestClient;
                            import io.vertx.core.Future;
                            import jakarta.ws.rs.GET;

                            @RestClient
                            @jakarta.ws.rs.Path("/foo")
                            public interface Foo {
                                @GET
                                Future<String> get();
                            }
                            """)));

            result.assertSuccess();
            // Proxy must be in the origin package (com.example), not the override (com.acme.gen)
            result.assertGeneratedSourceContains("com.example.Foo_RestClientProxy", "implements Foo");
        }

        @Test
        @DisplayName("proxy FQN matches what GeneratedNames.companionFqn would produce")
        void proxyFqnMatchesGeneratedNamesFqn() {
            // GeneratedNames.companionFqn(Foo.class, "_RestClientProxy")
            // = "com.example.Foo".replace('$','_') + "_RestClientProxy"
            // = "com.example.Foo_RestClientProxy"
            // The emitter must emit at exactly that FQN.
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    Map.of("vertique.codegen.package", "com.override.pkg"),
                    RestClientProcessorFixtures.allStubsWith(SourceFiles.inline("com.example.MyClient", """
                            package com.example;

                            import dev.vertique.rest.client.RestClient;
                            import io.vertx.core.Future;
                            import jakarta.ws.rs.GET;

                            @RestClient
                            public interface MyClient {
                                @GET
                                Future<String> get();
                            }
                            """)));

            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.MyClient_RestClientProxy", "implements MyClient");
        }
    }

    // --- @BeanParam accessor package isolation ---

    @Nested
    @DisplayName("@BeanParam accessor always lands in the bean's origin package, not the override")
    class BeanAccessorPackageIsolation {

        @Test
        @DisplayName("accessor FQN matches what BeanParamAccessorRegistry (GeneratedNames.companionFqn) would look up")
        void beanAccessorLandsInOriginPackage_notOverride() {
            // BeanParamAccessorRegistry.doResolve() calls GeneratedNames.companionFqn(beanType, "_BeanParamAccessor")
            // which always uses the origin package.  If BeanAccessorEmitter incorrectly calls
            // ctx.outputPackage() (which honours -Avertique.codegen.package), the emitted class would
            // land in com.override.pkg while the runtime looks for com.example.PageRequest_BeanParamAccessor.
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    Map.of("vertique.codegen.package", "com.override.pkg"),
                    RestClientProcessorFixtures.allStubsWith(
                            SourceFiles.inline("com.example.PageRequest", """
                                    package com.example;
                                    import jakarta.ws.rs.QueryParam;
                                    public record PageRequest(
                                        @QueryParam("page") int page,
                                        @QueryParam("size") int size
                                    ) {}
                                    """),
                            SourceFiles.inline("com.example.UserClient", """
                                    package com.example;
                                    import dev.vertique.rest.client.RestClient;
                                    import io.vertx.core.Future;
                                    import jakarta.ws.rs.BeanParam;
                                    import jakarta.ws.rs.GET;
                                    import java.util.List;
                                    @RestClient
                                    public interface UserClient {
                                        @GET Future<List<String>> list(@BeanParam PageRequest paging);
                                    }
                                    """)));

            result.assertSuccess();
            // Accessor must be in the origin package (com.example), not the override (com.override.pkg)
            result.assertGeneratedSourceContains("com.example.PageRequest_BeanParamAccessor", "BeanParamAccessor");
        }
    }

    // --- Nested interface flattening ---

    @Nested
    @DisplayName("nested @RestClient interface produces a flattened proxy name")
    class NestedInterfaceFlattening {

        @Test
        @DisplayName("Outer.Inner client emits Outer_Inner_RestClientProxy in origin package")
        void nestedClientEmitsFlattenedProxy() {
            // A nested interface com.example.Outer.Inner must produce
            // com.example.Outer_Inner_RestClientProxy, not com.example.Inner_RestClientProxy.
            // If the emitter uses clientType.getSimpleName() it would emit Inner_RestClientProxy,
            // which GeneratedNames.companionFqn (Outer$Inner -> Outer_Inner_...) would not find.
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(SourceFiles.inline("com.example.Outer", """
                            package com.example;

                            import dev.vertique.rest.client.RestClient;
                            import io.vertx.core.Future;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            public interface Outer {
                                @RestClient
                                @Path("/inner")
                                interface Inner {
                                    @GET
                                    Future<String> get();
                                }
                            }
                            """)));

            result.assertSuccess();
            // Must emit in origin package with flattened name
            result.assertGeneratedSourceContains("com.example.Outer_Inner_RestClientProxy", "implements Outer.Inner");
        }

        @Test
        @DisplayName("flattened proxy class name uses _ to join enclosing type names")
        void flattenedProxyClassNameUsesUnderscore() {
            var result = ProcessorTestHarness.run(
                    new RestClientProcessor(),
                    RestClientProcessorFixtures.allStubsWith(SourceFiles.inline("com.example.Outer", """
                            package com.example;

                            import dev.vertique.rest.client.RestClient;
                            import io.vertx.core.Future;
                            import jakarta.ws.rs.GET;

                            public interface Outer {
                                @RestClient
                                interface Inner {
                                    @GET
                                    Future<String> get();
                                }
                            }
                            """)));

            result.assertSuccess();
            // Class name in the source must be Outer_Inner_RestClientProxy, not Inner_RestClientProxy
            result.assertGeneratedSourceContains(
                    "com.example.Outer_Inner_RestClientProxy", "class Outer_Inner_RestClientProxy");
        }
    }
}
