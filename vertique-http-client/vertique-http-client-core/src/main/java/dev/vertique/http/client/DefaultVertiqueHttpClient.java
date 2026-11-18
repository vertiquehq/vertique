// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.internal.EgressInterceptor;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** The client {@link VertiqueHttpClientBuilder} builds: one agent, one scoped web client per call. */
final class DefaultVertiqueHttpClient implements VertiqueHttpClient {

    private final Vertx vertx;
    private final String name;
    private final HttpClientAgent agent;
    private final WebClientOptions webClientOptions;
    private final ClientSettings settings;
    private final AttemptScope clientScope = new AttemptScope();
    private final WebClient webClient;
    private final AtomicReference<Future<Void>> closeFuture = new AtomicReference<>();

    DefaultVertiqueHttpClient(
            Vertx vertx,
            String name,
            HttpClientAgent agent,
            WebClientOptions webClientOptions,
            ClientSettings settings) {
        this.vertx = vertx;
        this.name = name;
        this.agent = agent;
        this.webClientOptions = webClientOptions;
        this.settings = settings;
        this.webClient = scopedWebClient(clientScope, settings.readTimeoutMs());
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
        Context context = caller != null ? caller : vertx.getOrCreateContext();
        Long callTimeoutMs = options.readTimeoutMs();
        long timeoutMs = callTimeoutMs != null ? callTimeoutMs : settings.readTimeoutMs();
        CallExecution<T> call = new CallExecution<>(vertx, context, options.cancellationSignal(), timeoutMs);
        return call.start(attempt, scopedWebClient(call.scope(), 0));
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

    private WebClient scopedWebClient(AttemptScope scope, long exchangeTimeoutMs) {
        WebClient governed = WebClient.wrap(agent, webClientOptions);
        // Order matters: PrepareInterceptor swaps the body codec by re-preparing the request, which runs
        // PREPARE_REQUEST again for every interceptor registered before it. Each of those must tolerate the
        // second pass, as ExchangeTracker does by keeping its exchange in the context.
        governed.addInterceptor(new ExchangeTracker(scope, vertx, exchangeTimeoutMs, settings.maxResponseBytes()));
        governed.addInterceptor(new PrepareInterceptor(settings));
        EgressPolicy egress = settings.egress();
        governed.addInterceptor(
                new EgressInterceptor(egress::check, egress.restrictsHosts(), webClientOptions.isSsl()));
        return new GovernedWebClient(governed, settings);
    }
}
