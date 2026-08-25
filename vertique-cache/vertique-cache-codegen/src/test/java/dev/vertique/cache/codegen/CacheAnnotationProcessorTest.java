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
                .assertSuccess()
                .assertGeneratedSourceContains("com.example.GeneratedCacheModule", "@Module")
                .assertGeneratedSourceContains("com.example.GeneratedCacheModule", "CacheCaffeineModule.class");
    }

    @Test
    @DisplayName("REST entity and Future entity results compile")
    void restEntityResultsCompile() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(), SourceFiles.inline("com.example.RestCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import io.vertx.core.Future;
                                import jakarta.inject.Inject;
                                import jakarta.ws.rs.GET;

                                public class RestCacheableBean {
                                    @Inject
                                    public RestCacheableBean() {}

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public String entity(String id) {
                                        return id;
                                    }

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public Future<String> futureEntity(String id) {
                                        return Future.succeededFuture(id);
                                    }
                                }
                                """))
                .assertSuccess();
    }

    @Test
    @DisplayName("REST Response results are rejected")
    void restResponseResultsAreRejected() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(), SourceFiles.inline("com.example.ResponseCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import jakarta.inject.Inject;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.core.Response;

                                public class ResponseCacheableBean {
                                    @Inject
                                    public ResponseCacheableBean() {}

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public Response response(String id) {
                                        return Response.ok(id).build();
                                    }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("REST cacheable methods must return an entity result");
    }

    @Test
    @DisplayName("REST Future Response results are rejected")
    void restFutureResponseResultsAreRejected() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(),
                        SourceFiles.inline("com.example.FutureResponseCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import io.vertx.core.Future;
                                import jakarta.inject.Inject;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.core.Response;

                                public class FutureResponseCacheableBean {
                                    @Inject
                                    public FutureResponseCacheableBean() {}

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public Future<Response> response(String id) {
                                        return Future.succeededFuture(Response.ok(id).build());
                                    }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("REST cacheable Future result must contain an entity");
    }

    @Test
    @DisplayName("REST Buffer results are rejected")
    void restBufferResultsAreRejected() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(), SourceFiles.inline("com.example.BufferCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import io.vertx.core.buffer.Buffer;
                                import jakarta.inject.Inject;
                                import jakarta.ws.rs.GET;

                                public class BufferCacheableBean {
                                    @Inject
                                    public BufferCacheableBean() {}

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public Buffer buffer(String id) {
                                        return null;
                                    }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("REST cacheable methods must return an entity result");
    }

    @Test
    @DisplayName("REST streaming results are rejected")
    void restStreamingResultsAreRejected() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(), SourceFiles.inline("com.example.StreamingCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import io.vertx.core.streams.ReadStream;
                                import jakarta.inject.Inject;
                                import jakarta.ws.rs.GET;

                                public class StreamingCacheableBean {
                                    @Inject
                                    public StreamingCacheableBean() {}

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public ReadStream<String> stream(String id) {
                                        return null;
                                    }
                                }
                """))
                .assertFailed()
                .assertErrorMessage("REST cacheable methods must return an entity result");
    }

    @Test
    @DisplayName("REST transport response results are rejected")
    void restTransportResponseResultsAreRejected() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(), SourceFiles.inline("com.example.TransportCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import io.vertx.core.http.HttpServerResponse;
                                import jakarta.inject.Inject;
                                import jakarta.ws.rs.GET;

                                public class TransportCacheableBean {
                                    @Inject
                                    public TransportCacheableBean() {}

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public HttpServerResponse response(String id) {
                                        return null;
                                    }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("REST cacheable methods must return an entity result");
    }

    @Test
    @DisplayName("REST transport subtypes are rejected")
    void restTransportSubtypesAreRejected() {
        ProcessorTestHarness.run(
                        new CacheAnnotationProcessor(),
                        SourceFiles.inline("com.example.TransportSubtypeCacheableBean", """
                                package com.example;

                                import dev.vertique.cache.Cacheable;
                                import io.vertx.core.Future;
                                import io.vertx.core.streams.ReadStream;
                                import jakarta.inject.Inject;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.core.Response;
                                import java.util.concurrent.Flow;

                                public class TransportSubtypeCacheableBean {
                                    @Inject
                                    public TransportSubtypeCacheableBean() {}

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public CustomResponse response(String id) {
                                        return null;
                                    }

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public Future<CustomPublisher> publisher(String id) {
                                        return null;
                                    }

                                    @GET
                                    @Cacheable(name = "users", key = "{0}")
                                    public CustomStream stream(String id) {
                                        return null;
                                    }
                                }

                                abstract class CustomResponse extends Response {}
                                interface CustomPublisher extends Flow.Publisher<String> {}
                                interface CustomStream extends ReadStream<String> {}
                                """))
                .assertFailed()
                .assertErrorMessage("REST cacheable");
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
