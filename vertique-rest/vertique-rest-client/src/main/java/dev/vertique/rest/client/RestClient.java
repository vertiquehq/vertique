// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a declarative REST client.
 *
 * <p>Annotate a JAX-RS-annotated interface with {@code @RestClient} to enable proxy generation via
 * {@link RestClientFactory} or {@link RestClientBuilder}. The annotation provides a logical name
 * for configuration lookup and per-client SPI resolution, and an optional default base URL.
 *
 * <pre>{@code
 * @RestClient(name = "user-service", value = "http://user-service:8080")
 * @Path("/users")
 * public interface UserClient {
 *     @GET
 *     @Path("/{id}")
 *     Future<User> getUser(@PathParam("id") String id);
 * }
 * }</pre>
 *
 * <p>To create a proxy, use {@link RestClientBuilder} directly:
 * <pre>{@code
 * UserClient client = new RestClientBuilder(vertx)
 *     .baseUrl("http://user-service:8080")
 *     .readTimeout(5, TimeUnit.SECONDS)
 *     .build(UserClient.class);
 * }</pre>
 *
 * <p>Or, when using Dagger, obtain a pre-seeded builder from the factory:
 * <pre>{@code
 * UserClient client = factory.builder()
 *     .baseUrl("http://user-service:8080")
 *     .build(UserClient.class);
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RestClient {

    /**
     * Logical name for config lookup and per-client SPI resolution.
     *
     * @return the client name, empty string if not specified
     */
    String name() default "";

    /**
     * Default base URL for this client. Overridden by the builder's {@code baseUrl(...)} setting
     * if provided.
     *
     * @return the default base URL, empty string if not specified
     */
    String value() default "";
}
