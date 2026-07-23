// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;

/**
 * Shared inline source fixtures used by {@code RestClientProcessor*Test} classes.
 *
 * <p>Fixtures are defined as string constants and exposed via static factory methods so they can
 * be composed into different compile-testing scenarios without duplication.
 */
final class RestClientProcessorFixtures {

    private RestClientProcessorFixtures() {}

    // --- Core annotation stubs ---

    static JavaFileObject restClientAnnotation() {
        return SourceFiles.inline("dev.vertique.rest.client.RestClient", """
                        package dev.vertique.rest.client;

                        import java.lang.annotation.*;

                        @Target(ElementType.TYPE)
                        @Retention(RetentionPolicy.RUNTIME)
                        @Documented
                        public @interface RestClient {
                            String name() default "";
                            String value() default "";
                        }
                        """);
    }

    static JavaFileObject futureClass() {
        return SourceFiles.inline("io.vertx.core.Future", """
                        package io.vertx.core;

                        public interface Future<T> {}
                        """);
    }

    static JavaFileObject noAutoWireAnnotation() {
        return SourceFiles.inline("dev.vertique.codegen.NoAutoWire", """
                        package dev.vertique.codegen;

                        import java.lang.annotation.*;

                        @Target(ElementType.TYPE)
                        @Retention(RetentionPolicy.SOURCE)
                        @Documented
                        public @interface NoAutoWire {}
                        """);
    }

    static JavaFileObject urlAnnotation() {
        return SourceFiles.inline("dev.vertique.rest.client.Url", """
                        package dev.vertique.rest.client;

                        import java.lang.annotation.*;

                        @Target({ElementType.PARAMETER})
                        @Retention(RetentionPolicy.RUNTIME)
                        @Documented
                        public @interface Url {}
                        """);
    }

    // --- Runtime stubs needed for proxy emission ---

    static JavaFileObject restClientDispatcher() {
        return SourceFiles.inline("dev.vertique.rest.client.RestClientDispatcher", """
                        package dev.vertique.rest.client;

                        import dev.vertique.rest.client.meta.ClientMethodMeta;
                        import io.vertx.core.Future;
                        import java.net.URI;

                        public interface RestClientDispatcher {
                            RestRequestBuilder newRequest(ClientMethodMeta meta);
                            <T> Future<T> send(RestRequestBuilder request, ClientMethodMeta meta);
                            RestRequestBuilder applyPathParam(RestRequestBuilder req, ClientMethodMeta meta, String paramName, Object value, String defaultValue);
                            RestRequestBuilder applyQueryParam(RestRequestBuilder req, ClientMethodMeta meta, String paramName, Object value, String defaultValue);
                            RestRequestBuilder applyHeaderParam(RestRequestBuilder req, ClientMethodMeta meta, String paramName, Object value, String defaultValue);
                            RestRequestBuilder applyCookieParam(RestRequestBuilder req, ClientMethodMeta meta, String paramName, Object value, String defaultValue);
                            RestRequestBuilder applyBody(RestRequestBuilder req, ClientMethodMeta meta, Object value);
                            RestRequestBuilder applyUrlParam(RestRequestBuilder req, ClientMethodMeta meta, URI value);
                        }
                        """);
    }

    static JavaFileObject restRequestBuilder() {
        return SourceFiles.inline("dev.vertique.rest.client.RestRequestBuilder", """
                        package dev.vertique.rest.client;

                        public final class RestRequestBuilder {
                            public RestRequestBuilder path(String name, Object value) { return this; }
                            public RestRequestBuilder query(String name, Object value) { return this; }
                            public RestRequestBuilder header(String name, String value) { return this; }
                            public RestRequestBuilder cookie(String name, String value) { return this; }
                            public RestRequestBuilder absoluteUri(String uri) { return this; }
                            public RestRequestBuilder bodyObject(Object body) { return this; }
                        }
                        """);
    }

    static JavaFileObject restClientException() {
        return SourceFiles.inline("dev.vertique.rest.client.exception.RestClientException", """
                        package dev.vertique.rest.client.exception;

                        public class RestClientException extends RuntimeException {
                            public RestClientException(String message) { super(message); }
                            public RestClientException(String message, Throwable cause) { super(message, cause); }
                        }
                        """);
    }

    static JavaFileObject beanParamAccessorRegistry() {
        return SourceFiles.inline("dev.vertique.rest.client.BeanParamAccessorRegistry", """
                        package dev.vertique.rest.client;

                        public final class BeanParamAccessorRegistry {
                            public <T> BeanParamAccessor<T> resolve(Class<T> beanType) { return null; }
                        }
                        """);
    }

    static JavaFileObject beanParamAccessor() {
        return SourceFiles.inline("dev.vertique.rest.client.BeanParamAccessor", """
                        package dev.vertique.rest.client;

                        import java.util.List;

                        public interface BeanParamAccessor<T> {
                            Object extract(T bean, String fieldName);
                            List<String> fieldNames();
                        }
                        """);
    }

    static JavaFileObject clientMethodMeta() {
        return SourceFiles.inline("dev.vertique.rest.client.meta.ClientMethodMeta", """
                        package dev.vertique.rest.client.meta;

                        public final class ClientMethodMeta {
                        }
                        """);
    }

    /** Returns all stubs needed for basic proxy emission tests. */
    static JavaFileObject[] allStubs() {
        return new JavaFileObject[] {
            restClientAnnotation(),
            futureClass(),
            noAutoWireAnnotation(),
            restClientDispatcher(),
            restRequestBuilder(),
            restClientException(),
            beanParamAccessorRegistry(),
            beanParamAccessor(),
            clientMethodMeta()
        };
    }

    /**
     * Combines {@link #allStubs()} with additional source files into a single array suitable for
     * passing to {@link dev.vertique.codegen.test.ProcessorTestHarness#run}.
     *
     * <p>This helper exists because Java varargs do not allow passing an array as the first of
     * multiple arguments without an explicit spread. Use this method instead of
     * {@code allStubs(), extra} in test methods.
     *
     * @param extra additional source files to append after the stubs
     * @return a new array containing all stubs followed by the extra files
     */
    static JavaFileObject[] allStubsWith(JavaFileObject... extra) {
        JavaFileObject[] stubs = allStubs();
        JavaFileObject[] combined = new JavaFileObject[stubs.length + extra.length];
        System.arraycopy(stubs, 0, combined, 0, stubs.length);
        System.arraycopy(extra, 0, combined, stubs.length, extra.length);
        return combined;
    }
}
