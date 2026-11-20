// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.config.HttpClientConfig;
import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides the governed HTTP clients of an application: one {@link HttpClient} per name,
 * configured from {@code httpClient.{name}} and seeded with the global
 * {@link HttpClientInterceptor}s of the Dagger graph.
 *
 * <p>A name without a section gets every default. The factory tracks every client it builds, including
 * those of its builders, and closes them when the application stops.
 */
@Singleton
public final class HttpClientFactory {

    private final Vertx vertx;
    private final List<HttpClientInterceptor> interceptors;
    private final Map<String, HttpClientConfig> index;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();
    private final Queue<HttpClient> built = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Future<Void>> closeFuture = new AtomicReference<>();

    @Inject
    HttpClientFactory(Vertx vertx, Set<HttpClientInterceptor> interceptors, Map<String, HttpClientConfig> index) {
        this.vertx = vertx;
        this.interceptors = List.copyOf(interceptors);
        this.index = index;
    }

    /**
     * Returns the client of {@code httpClient.{name}}, building it on first use. Later calls with the
     * same name return the same instance.
     *
     * @param name the client name, {@code [A-Za-z0-9._~-]{1,128}}
     * @return the client
     * @throws NullPointerException when {@code name} is null
     * @throws dev.vertique.http.client.exception.HttpClientConfigurationException when the name or the
     *     configured values are invalid
     * @throws HttpClientClosedException when the application has stopped
     */
    public HttpClient client(String name) {
        Objects.requireNonNull(name, "name");
        return clients.computeIfAbsent(name, key -> newBuilder(key).build());
    }

    /**
     * Creates a builder seeded from {@code httpClient.{name}} and the global interceptors. The factory
     * closes every client the builder builds when the application stops.
     *
     * @param name the client name, {@code [A-Za-z0-9._~-]{1,128}}
     * @return a new builder
     * @throws HttpClientClosedException when the application has stopped
     */
    public HttpClientBuilder builder(String name) {
        return newBuilder(name);
    }

    /** Closes every client the factory handed out; repeated calls return the same future. */
    Future<Void> close() {
        Promise<Void> closing = Promise.promise();
        if (!closeFuture.compareAndSet(null, closing.future())) {
            return closeFuture.get();
        }
        List<Future<Void>> closures = built.stream().map(HttpClient::close).toList();
        Future<Void> all = closures.isEmpty()
                ? Future.succeededFuture()
                : Future.join(closures).mapEmpty();
        all.onComplete(closing);
        return closing.future();
    }

    private HttpClientBuilder newBuilder(String name) {
        if (closeFuture.get() != null) {
            throw new HttpClientClosedException();
        }
        HttpClientBuilder builder = HttpClientBuilder.create(vertx)
                .name(name)
                .config(index.get(name))
                .onBuilt(this::track);
        interceptors.forEach(builder::register);
        return builder;
    }

    /** Records a client the factory must close; a client built after the factory closed is closed at once. */
    private void track(HttpClient client) {
        built.add(client);
        if (closeFuture.get() != null) {
            // close() may have taken its snapshot before the add; closing twice returns the same future
            client.close();
            throw new HttpClientClosedException();
        }
    }
}
