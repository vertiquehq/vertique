// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.config.HttpClientConfig;
import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides the governed HTTP clients of an application: one {@link VertiqueHttpClient} per name,
 * configured from {@code httpClient.{name}} and seeded with the global
 * {@link HttpClientInterceptor}s of the Dagger graph.
 *
 * <p>A name without a section gets every default. The factory owns the clients and builders it hands
 * out and closes them when the application stops.
 */
@Singleton
public final class HttpClientFactory {

    private final Vertx vertx;
    private final Resilience resilience;
    private final List<HttpClientInterceptor> interceptors;
    private final Map<String, HttpClientConfig> index;
    private final ResiliencePolicyRegistry policies;
    private final Map<String, VertiqueHttpClient> clients = new HashMap<>();
    private final List<VertiqueHttpClientBuilder> builders = new ArrayList<>();
    private final AtomicReference<Future<Void>> closeFuture = new AtomicReference<>();

    @Inject
    HttpClientFactory(
            Vertx vertx,
            Resilience resilience,
            Set<HttpClientInterceptor> interceptors,
            Map<String, HttpClientConfig> index,
            Optional<ResiliencePolicyRegistry> policies) {
        this.vertx = vertx;
        this.resilience = resilience;
        this.interceptors = List.copyOf(interceptors);
        this.index = index;
        this.policies = policies.orElseGet(ResiliencePolicyRegistry::empty);
    }

    /**
     * Returns the client of {@code httpClient.{name}}, building it on first use. Later calls with the
     * same name return the same instance.
     *
     * @param name the client name, {@code [A-Za-z0-9._~-]{1,128}}
     * @return the client
     * @throws dev.vertique.http.client.exception.HttpClientConfigurationException when the name or the
     *     configured values are invalid
     * @throws HttpClientClosedException when the application has stopped
     */
    public synchronized VertiqueHttpClient client(String name) {
        VertiqueHttpClient existing = clients.get(name);
        if (existing != null) {
            return existing;
        }
        VertiqueHttpClient built = newBuilder(name).build();
        clients.put(name, built);
        return built;
    }

    /**
     * Creates a builder seeded from {@code httpClient.{name}} and the global interceptors. The factory
     * closes the clients the builder builds when the application stops; the caller may close the
     * builder earlier.
     *
     * @param name the client name, {@code [A-Za-z0-9._~-]{1,128}}
     * @return a new builder
     * @throws HttpClientClosedException when the application has stopped
     */
    public synchronized VertiqueHttpClientBuilder builder(String name) {
        return newBuilder(name);
    }

    /** Closes every client and builder the factory handed out; repeated calls return the same future. */
    synchronized Future<Void> close() {
        Future<Void> existing = closeFuture.get();
        if (existing != null) {
            return existing;
        }
        List<Future<Void>> closures =
                builders.stream().map(VertiqueHttpClientBuilder::close).toList();
        Future<Void> result = closures.isEmpty()
                ? Future.succeededFuture()
                : Future.join(closures).mapEmpty();
        closeFuture.set(result);
        return result;
    }

    private VertiqueHttpClientBuilder newBuilder(String name) {
        if (closeFuture.get() != null) {
            throw new HttpClientClosedException();
        }
        VertiqueHttpClientBuilder builder = VertiqueHttpClientBuilder.create(vertx, resilience)
                .name(name)
                .policyRegistry(policies)
                .config(index.get(name));
        interceptors.forEach(builder::register);
        builders.add(builder);
        return builder;
    }
}
