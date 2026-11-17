// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** The client {@link VertiqueHttpClientBuilder} builds: one agent, one scoped web client per call. */
final class DefaultVertiqueHttpClient implements VertiqueHttpClient {

    private final String name;
    private final HttpClientAgent agent;
    private final WebClientOptions webClientOptions;
    private final ClientSettings settings;
    private final AttemptScope clientScope = new AttemptScope();
    private final WebClient webClient;
    private final AtomicReference<Future<Void>> closeFuture = new AtomicReference<>();

    DefaultVertiqueHttpClient(
            String name, HttpClientAgent agent, WebClientOptions webClientOptions, ClientSettings settings) {
        this.name = name;
        this.agent = agent;
        this.webClientOptions = webClientOptions;
        this.settings = settings;
        this.webClient = scopedWebClient(clientScope);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public WebClient webClient() {
        return webClient;
    }

    ClientSettings settings() {
        return settings;
    }

    @Override
    public <T> Future<T> execute(Function<WebClient, Future<T>> attempt) {
        return execute(CallOptions.defaults(), attempt);
    }

    @Override
    public <T> Future<T> execute(CallOptions options, Function<WebClient, Future<T>> attempt) {
        if (options == null || attempt == null) {
            return Future.failedFuture(new IllegalArgumentException("The call options and the attempt are required"));
        }
        if (closeFuture.get() != null) {
            return Future.failedFuture(new IllegalStateException("The HTTP client is closed"));
        }
        Context caller = Vertx.currentContext();
        AttemptScope scope = new AttemptScope();
        Future<T> result;
        try {
            result = attempt.apply(scopedWebClient(scope));
            if (result == null) {
                result = Future.failedFuture(new IllegalStateException("The attempt returned no future"));
            }
        } catch (RuntimeException failure) {
            result = Future.failedFuture(failure);
        }
        Promise<T> promise = Promise.promise();
        result.onComplete(outcome -> {
            scope.end();
            if (caller == null || Vertx.currentContext() == caller) {
                promise.handle(outcome);
            } else {
                caller.runOnContext(ignored -> promise.handle(outcome));
            }
        });
        return promise.future();
    }

    @Override
    public Future<Void> close() {
        Future<Void> existing = closeFuture.get();
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = closeFuture.get();
            if (existing != null) {
                return existing;
            }
            clientScope.end();
            Future<Void> closing = agent.close();
            closeFuture.set(closing);
            return closing;
        }
    }

    private WebClient scopedWebClient(AttemptScope scope) {
        WebClient governed = WebClient.wrap(agent, webClientOptions);
        governed.addInterceptor(new ExchangeTracker(scope));
        governed.addInterceptor(new PrepareInterceptor(settings));
        return new GovernedWebClient(governed, settings);
    }
}
