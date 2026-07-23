// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.rest.client.exception.RestClientConfigurationException;
import dev.vertique.rest.core.convert.ParamConverter;
import dev.vertique.rest.core.convert.ParamConverterBinding;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Build-time converter-validation tests for {@link RestClientBuilder}.
 *
 * <p>These verify Slice 4.2's contract that {@link RestClientBuilder#build(Class)} fails fast (at
 * client-build time, never at first dispatch) when a declared convertible parameter — a
 * {@code @QueryParam}/{@code @PathParam}/{@code @HeaderParam}/{@code @CookieParam} — has a target
 * type the build's effective {@link dev.vertique.rest.core.convert.ParamConversionResolver} cannot
 * resolve. A built-in type (UUID, enum, primitives) requires no extra wiring; a custom type must be
 * supplied an app converter via the new standalone-builder fluent surface
 * ({@code paramConverterBinding(...)} / {@code paramConverterProvider(...)} /
 * {@code paramConversionResolver(...)}) or build fails with {@link RestClientConfigurationException}
 * naming the offending parameter and type.
 *
 * <p><strong>RED status:</strong> compile-RED today — the {@code dev.vertique.rest.core.convert}
 * types are not yet on the rest-client classpath (the pom has no {@code vertique-rest-core}
 * dependency) and the {@code paramConverterBinding(...)} / {@code paramConverterProvider(...)}
 * fluent setters do not exist. Even once those compile, the assertions are behavior-RED:
 * {@code build()} performs no converter validation today, so the missing-converter cases would
 * build successfully instead of throwing.
 */
@DisplayName("RestClientBuilder build-time converter validation")
class RestClientBuildValidationTest {

    // --- Shared Vert.x ---

    static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            vertx.close();
        }
    }

    // --- Fixtures: a custom domain type with no built-in converter ---

    /** A custom value type that no built-in converter can resolve. */
    record MyType(String raw) {}

    /** Client interface declaring a {@code @QueryParam} of the unconvertible custom type. */
    @Path("/things")
    interface MyTypeClient {
        @GET
        Future<String> find(@QueryParam("q") MyType q);
    }

    /** Client interface declaring only a built-in-convertible {@code @QueryParam} (UUID). */
    @Path("/things")
    interface UuidClient {
        @GET
        Future<String> find(@QueryParam("id") java.util.UUID id);
    }

    /** A converter for {@link MyType} suitable for a {@link ParamConverterBinding}. */
    static final ParamConverter<MyType> MY_TYPE_CONVERTER = new ParamConverter<>() {
        @Override
        public MyType fromString(String value) {
            return new MyType(value);
        }

        @Override
        public String toString(MyType value) {
            return value.raw();
        }
    };

    /** A JAX-RS provider that supplies a converter for {@link MyType}. */
    static final ParamConverterProvider MY_TYPE_PROVIDER = new ParamConverterProvider() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> jakarta.ws.rs.ext.ParamConverter<T> getConverter(
                Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType != MyType.class) {
                return null;
            }
            return (jakarta.ws.rs.ext.ParamConverter<T>) new jakarta.ws.rs.ext.ParamConverter<MyType>() {
                @Override
                public MyType fromString(String value) {
                    return new MyType(value);
                }

                @Override
                public String toString(MyType value) {
                    return value.raw();
                }
            };
        }
    };

    // --- Tests ---

    @Test
    @DisplayName("standalone builder fails to build a client whose @QueryParam type has no converter")
    void standaloneBuilderMissingConverterFails() {
        assertThrows(
                RestClientConfigurationException.class,
                () -> RestClientBuilder.create(vertx)
                        .baseUrl("http://localhost:9999")
                        .build(MyTypeClient.class),
                "build() should fail fast naming the param and type when no converter resolves MyType");
    }

    @Test
    @DisplayName("standalone builder with a fluent ParamConverterBinding builds successfully")
    void standaloneBuilderWithFluentBindingSucceeds() {
        assertDoesNotThrow(() -> RestClientBuilder.create(vertx)
                .baseUrl("http://localhost:9999")
                .paramConverterBinding(new ParamConverterBinding<>(MyType.class, MY_TYPE_CONVERTER))
                .build(MyTypeClient.class));
    }

    @Test
    @DisplayName("standalone builder with a fluent ParamConverterProvider builds successfully")
    void standaloneBuilderWithFluentProviderSucceeds() {
        assertDoesNotThrow(() -> RestClientBuilder.create(vertx)
                .baseUrl("http://localhost:9999")
                .paramConverterProvider(MY_TYPE_PROVIDER)
                .build(MyTypeClient.class));
    }

    @Test
    @DisplayName("standalone builder builds a built-in-type (UUID) client with no extra converter wiring")
    void builtInTypeBuildsWithNoExtraWiring() {
        assertDoesNotThrow(() ->
                RestClientBuilder.create(vertx).baseUrl("http://localhost:9999").build(UuidClient.class));
    }
}
