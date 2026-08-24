// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.codegen;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CacheAnnotationProcessorTest {

    @Test
    @DisplayName("positional and named selectors compile")
    void positionalAndNamedSelectorsCompile() {
        ProcessorTestHarness.run(new CacheAnnotationProcessor(), SourceFiles.inline("com.example.CacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import jakarta.inject.Inject;

                                public class CacheableBean {
                                    @Inject
                                    public CacheableBean() {}

                                    @Cacheable(name = "users", key = "{0}")
                                    public String byId(String id) {
                                        return id;
                                    }

                                    @Cacheable(name = "users", key = "{userId}")
                                    public String byNamedParameter(String userId) {
                                        return userId;
                                    }
                                }
                                """))
                .assertSuccess();
    }

    @Test
    @DisplayName("unsupported selector shapes are rejected")
    void unsupportedSelectorShapesAreRejected() {
        JavaFileObject source = SourceFiles.inline("com.example.InvalidCacheableBean", """
                package com.example;

                import dev.vertique.cache.Cacheable;
                import jakarta.inject.Inject;
                import java.util.List;

                public class InvalidCacheableBean {
                    @Inject
                    public InvalidCacheableBean() {}

                    @Cacheable(name = "users", key = "{value}")
                    public String objectValue(List<String> value) {
                        return value.getFirst();
                    }
                }
                """);

        ProcessorTestHarness.run(new CacheAnnotationProcessor(), source)
                .assertFailed()
                .assertErrorMessage("supported scalar");
    }

    @Test
    @DisplayName("property paths are bounded and resolve through public accessors")
    void propertyPathsAreBoundedAndAccessible() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(), SourceFiles.inline("com.example.PropertyCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import jakarta.inject.Inject;

                                public class PropertyCacheableBean {
                                    @Inject
                                    public PropertyCacheableBean() {}

                                    @Cacheable(name = "users", key = "{user.email}")
                                    public String byUser(User user) {
                                        return user.email();
                                    }

                                    public record User(String email) {}
                                }
                                """))
                .assertSuccess();
    }

    @Test
    @DisplayName("unbounded property paths are rejected")
    void excessivePropertyDepthIsRejected() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(), SourceFiles.inline("com.example.DeepCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import jakarta.inject.Inject;

                                public class DeepCacheableBean {
                                    @Inject
                                    public DeepCacheableBean() {}

                                    @Cacheable(name = "users", key = "{user.a.b.c.d}")
                                    public String byUser(User user) {
                                        return "value";
                                    }

                                    public record User(String a) {}
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("limited to three properties");
    }

    @Test
    @DisplayName("raw and wildcard futures are rejected")
    void ambiguousFutureResultsAreRejected() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(), SourceFiles.inline("com.example.FutureCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import io.vertx.core.Future;
                                import jakarta.inject.Inject;

                                public class FutureCacheableBean {
                                    @Inject
                                    public FutureCacheableBean() {}

                                    @Cacheable(name = "users", key = "{0}")
                                    @SuppressWarnings("rawtypes")
                                    public Future raw(String id) { return Future.succeededFuture(id); }

                                    @Cacheable(name = "users", key = "{0}")
                                    public Future<? extends CharSequence> wildcard(String id) {
                                        return Future.succeededFuture(id);
                                    }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("concrete Future");
    }

    @Test
    @DisplayName("non-proxyable cache methods are rejected")
    void nonProxyableMethodsAreRejected() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(),
                        SourceFiles.inline("com.example.NonProxyableCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import jakarta.inject.Inject;

                                public class NonProxyableCacheableBean {
                                    @Inject
                                    public NonProxyableCacheableBean() {}

                                    @Cacheable(name = "users", key = "{0}")
                                    public final String finalMethod(String id) { return id; }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("can be overridden");
    }
}
