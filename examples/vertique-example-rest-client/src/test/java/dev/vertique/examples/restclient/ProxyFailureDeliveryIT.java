// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.restclient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.client.RestClient;
import dev.vertique.rest.client.RestClientBuilder;
import dev.vertique.rest.client.Url;
import dev.vertique.rest.client.exception.RestClientException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves the generated proxy and the JDK reflective proxy deliver request-construction failures the
 * same way: the call returns a failed future carrying a {@link RestClientException}; it never throws
 * from the call site. No server is needed because every request fails before it is sent.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ProxyFailureDeliveryIT {

    /** A body whose serialization always fails. */
    public static final class Unserializable {
        public String getBad() {
            throw new IllegalStateException("cannot serialize");
        }
    }

    /** Generated-proxy client: the processor emits a companion for this interface. */
    @RestClient(name = "generatedFailure")
    interface GeneratedFailureClient {
        @GET
        @Path("/items/{id}")
        Future<Void> byPath(@PathParam("id") String id);

        @GET
        Future<Void> byUrl(@Url URI target);

        @POST
        @Path("/items")
        @Consumes("application/json")
        Future<Void> withBody(Unserializable body);
    }

    /** JDK-proxy client: same shape, no {@code @RestClient}, so no companion is generated. */
    interface JdkFailureClient {
        @GET
        @Path("/items/{id}")
        Future<Void> byPath(@PathParam("id") String id);

        @GET
        Future<Void> byUrl(@Url URI target);

        @POST
        @Path("/items")
        @Consumes("application/json")
        Future<Void> withBody(Unserializable body);
    }

    private static void assertFailedFuture(Future<Void> future, String methodName) {
        assertTrue(future.failed(), methodName + " must return an already-failed future");
        Throwable failure = future.cause();
        assertInstanceOf(RestClientException.class, failure);
        assertEquals("Failed to build request for " + methodName, failure.getMessage());
        assertNotNull(failure.getCause(), "the construction failure is kept as the cause");
    }

    @Test
    @DisplayName("generated proxy delivers request-construction failures as failed futures")
    void generatedProxy(Vertx vertx) {
        GeneratedFailureClient client =
                RestClientBuilder.create(vertx).baseUrl("http://127.0.0.1:1").build(GeneratedFailureClient.class);
        assertTrue(
                client.getClass().getSimpleName().endsWith("_RestClientProxy"),
                "expected generated proxy, got " + client.getClass().getName());

        assertFailedFuture(assertDoesNotThrow(() -> client.byPath(null)), "byPath");
        assertFailedFuture(assertDoesNotThrow(() -> client.byUrl(URI.create("/relative"))), "byUrl");
        assertFailedFuture(assertDoesNotThrow(() -> client.withBody(new Unserializable())), "withBody");
    }

    @Test
    @DisplayName("JDK proxy delivers request-construction failures as failed futures")
    void jdkProxy(Vertx vertx) {
        JdkFailureClient client =
                RestClientBuilder.create(vertx).baseUrl("http://127.0.0.1:1").build(JdkFailureClient.class);
        assertTrue(
                Proxy.isProxyClass(client.getClass()),
                "expected JDK proxy, got " + client.getClass().getName());

        assertFailedFuture(assertDoesNotThrow(() -> client.byPath(null)), "byPath");
        assertFailedFuture(assertDoesNotThrow(() -> client.byUrl(URI.create("/relative"))), "byUrl");
        assertFailedFuture(assertDoesNotThrow(() -> client.withBody(new Unserializable())), "withBody");
    }
}
