// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.http.client.internal.CredentialInterceptor;
import dev.vertique.http.client.internal.EgressInterceptor;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** The client {@link VertiqueHttpClientBuilder} builds: one agent, one scoped web client per call. */
final class DefaultVertiqueHttpClient implements VertiqueHttpClient {

    private final Vertx vertx;
    private final String name;
    private final HttpClientAgent agent;
    private final WebClientOptions webClientOptions;
    private final ClientSettings settings;
    private final HttpClientInterceptors interceptors;
    private final AttemptScope clientScope;
    private final WebClient webClient;
    private final ClientResilience resilience;
    private final Set<CallExecution<?>> activeCalls = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Future<Void>> closeFuture = new AtomicReference<>();

    DefaultVertiqueHttpClient(
            Vertx vertx,
            String name,
            HttpClientAgent agent,
            WebClientOptions webClientOptions,
            ClientSettings settings,
            List<HttpClientInterceptor> registered,
            @Nullable Resilience runtime,
            ResiliencePolicyRegistry policies,
            @Nullable String defaultPolicy) {
        this.vertx = vertx;
        this.name = name;
        this.agent = agent;
        this.webClientOptions = webClientOptions;
        this.settings = settings;
        this.interceptors = HttpClientInterceptors.of(registered, name, webClientOptions.isSsl());
        this.clientScope = new AttemptScope(interceptors, null, 1);
        this.resilience = new ClientResilience(runtime, policies, name, defaultPolicy);
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
            return Future.failedFuture(new HttpClientClosedException());
        }
        Context caller = Vertx.currentContext();
        Context context = caller != null ? caller : vertx.getOrCreateContext();
        ClientResilience.Selection selection;
        try {
            selection = resilience.select(options);
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
        Long callTimeoutMs = options.readTimeoutMs();
        Long policyTimeoutMs = selection.policyTimeoutMs();
        long timeoutMs = callTimeoutMs != null
                ? callTimeoutMs
                : policyTimeoutMs != null ? policyTimeoutMs : settings.readTimeoutMs();
        CallExecution<T> call = new CallExecution<>(
                vertx,
                context,
                options.cancellationSignal(),
                timeoutMs,
                interceptors,
                selection,
                resilience,
                scope -> scopedWebClient(scope, 0),
                activeCalls::remove);
        activeCalls.add(call);
        if (closeFuture.get() != null) {
            call.cancel(new HttpClientClosedException());
        }
        return call.start(attempt);
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
            clientScope.end(null);
            // Publish the closing marker before the snapshot of the running calls: a call that registers
            // after the snapshot re-checks the marker and cancels itself.
            Promise<Void> closed = Promise.promise();
            closeFuture.set(closed.future());
            Future<Void> resilienceClosed = resilienceClosing();
            Future.all(List.of(agent.close(), resilienceClosed))
                    .<Void>mapEmpty()
                    .onComplete(closed);
            return closed.future();
        }
    }

    /** The number of timeouts the client currently records as not retryable. */
    int nonRetryableTimeoutCount() {
        return resilience.refusedTimeoutCount();
    }

    /** Fails every running call with the closed failure, then closes the pipelines' state. */
    private Future<Void> resilienceClosing() {
        for (CallExecution<?> call : new ArrayList<>(activeCalls)) {
            call.cancel(new HttpClientClosedException());
        }
        return resilience.close();
    }

    private WebClient scopedWebClient(AttemptScope scope, long exchangeTimeoutMs) {
        WebClient governed = WebClient.wrap(agent, webClientOptions);
        // Order matters: PrepareInterceptor swaps the body codec by re-preparing the request, which runs
        // PREPARE_REQUEST again for every interceptor registered before it. Each of those must tolerate the
        // second pass, as ExchangeTracker does by keeping its exchange in the context.
        governed.addInterceptor(new ExchangeTracker(scope, vertx, exchangeTimeoutMs, settings.maxResponseBytes()));
        governed.addInterceptor(new PrepareInterceptor(settings, interceptors));
        EgressPolicy egress = settings.egress();
        governed.addInterceptor(
                new EgressInterceptor(egress::check, egress.restrictsHosts(), webClientOptions.isSsl()));
        if (settings.credentials() != null) {
            governed.addInterceptor(new CredentialInterceptor(
                    settings.credentials(),
                    settings.allowCredentialsOverPlainHttp(),
                    Set.copyOf(settings.credentialOrigins()),
                    settings.hasBaseUrl(),
                    webClientOptions.isSsl(),
                    webClientOptions.getDefaultHost(),
                    webClientOptions.getDefaultPort(),
                    EgressPolicy::isLoopbackHost));
        }
        return new GovernedWebClient(governed, settings);
    }
}
