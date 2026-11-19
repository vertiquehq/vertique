// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A governed outbound HTTP client. Requests are built and sent with the Vert.x {@link WebClient} API;
 * every {@code WebClient} this client hands out is a framework delegate whose {@code close()} does
 * nothing and whose {@code addInterceptor} is refused.
 *
 * <p>Create instances with {@link HttpClientBuilder}. Futures returned by this client complete
 * on the caller's Vert.x context when it is called on one.
 */
public final class HttpClient {

    /** Message of the failure a stream request not created by the call's web client fails with. */
    static final String FOREIGN_REQUEST = "The stream request was not created by the web client of the call";

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
    private final Set<ActiveStream> openStreams = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Future<Void>> closeFuture = new AtomicReference<>();

    HttpClient(
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
        this.webClient = scopedWebClient(clientScope, settings.readTimeoutMs(), false);
    }

    /**
     * Returns the client name.
     *
     * @return the name given to the builder, {@code "default"} when none was set
     */
    public String name() {
        return name;
    }

    /**
     * Returns the governed {@code WebClient} for direct use. Every request sent through it runs one
     * attempt with the client's defaults. The instance stays valid until {@link #close()}; closing it
     * leaves this client usable.
     *
     * @return the governed web client
     */
    public WebClient webClient() {
        return webClient;
    }

    ClientSettings settings() {
        return settings;
    }

    /**
     * Runs {@code attempt} once with a fresh governed {@code WebClient} scoped to this call, using the
     * client's default call options.
     *
     * <p>When the future returned by {@code attempt} settles, every exchange the attempt started that is
     * still open is reset and the scoped {@code WebClient} fails every later request with
     * {@link IllegalStateException}. A {@code null} argument fails the returned future with
     * {@link IllegalArgumentException}.
     *
     * @param attempt builds and sends the requests of the attempt and returns its result
     * @param <T> the result type
     * @return the attempt's result
     */
    public <T> Future<T> execute(Function<WebClient, Future<T>> attempt) {
        return execute(CallOptions.defaults(), attempt);
    }

    /**
     * Runs {@code attempt} once with a fresh governed {@code WebClient} scoped to this call.
     *
     * <p>Behaves like {@link #execute(Function)} with the given call options. A {@code null} argument
     * fails the returned future with {@link IllegalArgumentException}.
     *
     * @param options the options of this call
     * @param attempt builds and sends the requests of the attempt and returns its result
     * @param <T> the result type
     * @return the attempt's result
     */
    public <T> Future<T> execute(CallOptions options, Function<WebClient, Future<T>> attempt) {
        if (options == null || attempt == null) {
            return Future.failedFuture(new IllegalArgumentException("The call options and the attempt are required"));
        }
        return run(options, scope -> scopedWebClient(scope, 0, false), attempt);
    }

    /**
     * Sends the request {@code request} builds and returns as soon as a response head the call's
     * expectation accepts has arrived, with the body still unread.
     *
     * <p>The call runs in the options' resilience pipeline and attempt timeout up to that head: a head
     * the expectation refuses fails the attempt with the expectation's failure (an
     * {@link dev.vertique.http.client.exception.HttpStatusRejectedException} for
     * {@link HttpExpectations#success()}) carrying at most 2048 body bytes, resets the exchange and is
     * retried like any rejection. From the accepted head on the call has left the pipeline: it is never
     * retried, the circuit breaker has recorded the head's outcome, a bulkhead permit is released, the
     * attempt timeout no longer applies and only the stream idle timeout governs the body. A bulkhead
     * therefore bounds how many heads are being obtained, not how many streams are open. A stream that
     * is not consumed holds its connection until it is cancelled, fails or the client closes.
     *
     * <p>The request must be created by the {@code WebClient} passed to {@code request}; any other
     * request fails the call with {@link IllegalArgumentException}. The body is read through the
     * returned stream and is not subject to {@code maxResponseBytes}. A {@code null} {@code options} or
     * {@code request} fails the returned future with {@link IllegalArgumentException}.
     *
     * @param options the options of this call
     * @param request builds the request from the attempt's web client
     * @param body the buffered request body, or {@code null} for none
     * @return the stream, at its accepted head
     */
    public Future<HttpStream> stream(
            CallOptions options, Function<WebClient, HttpRequest<Buffer>> request, @Nullable Buffer body) {
        if (options == null || request == null) {
            return Future.failedFuture(
                    new IllegalArgumentException("The call options and the request function are required"));
        }
        List<ActiveStream> produced = new CopyOnWriteArrayList<>();
        Future<HttpStream> result = run(
                options,
                scope -> scopedWebClient(scope, 0, true),
                web -> openStream(options, request, body, web, produced));
        // A stream the call did not deliver (it was cancelled or settled first) has no owner: end it.
        result.onComplete(outcome -> {
            for (ActiveStream stream : produced) {
                if (outcome.failed() || outcome.result() != stream) {
                    stream.cancel(outcome.failed() ? outcome.cause() : new HttpExchangeCancelledException());
                }
            }
        });
        return result;
    }

    /** One attempt of a stream call: sends the request and returns the future of the accepted head's stream. */
    private Future<HttpStream> openStream(
            CallOptions options,
            Function<WebClient, HttpRequest<Buffer>> request,
            @Nullable Buffer body,
            WebClient web,
            List<ActiveStream> produced) {
        WebClientView governed = (WebClientView) web;
        HttpRequest<Buffer> built = request.apply(web);
        if (built == null || !governed.created(built)) {
            throw new IllegalArgumentException(FOREIGN_REQUEST);
        }
        StreamCodec codec = new StreamCodec();
        StreamAttempt attempt = new StreamAttempt(
                vertx,
                governed.scope(),
                options.expectation(),
                settings.streamIdleTimeoutMs(),
                settings.maxEventBytes(),
                codec,
                this::streamOpened,
                openStreams::remove);
        governed.scope().streamAttempt(attempt);
        HttpRequest<Void> streaming = built.as(codec);
        Future<HttpResponse<Void>> sent = body == null ? streaming.send() : streaming.sendBuffer(body);
        sent.onFailure(attempt::failHead);
        attempt.head().onSuccess(stream -> produced.add((ActiveStream) stream));
        return attempt.head();
    }

    private void streamOpened(ActiveStream stream) {
        openStreams.add(stream);
        if (closeFuture.get() != null) {
            stream.cancel(new HttpClientClosedException());
        }
    }

    private <T> Future<T> run(
            CallOptions options, Function<AttemptScope, WebClient> webClients, Function<WebClient, Future<T>> attempt) {
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
                webClients,
                activeCalls::remove);
        activeCalls.add(call);
        if (closeFuture.get() != null) {
            call.cancel(new HttpClientClosedException());
        }
        return call.start(attempt);
    }

    /**
     * Closes the client and its HTTP agent. Requests still open are reset and open streams fail with
     * {@link dev.vertique.http.client.exception.HttpClientClosedException}. Closing again returns the
     * same outcome.
     *
     * @return a future that completes when the agent is closed
     */
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
            clientScope.end(new HttpClientClosedException());
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
        for (ActiveStream stream : new ArrayList<>(openStreams)) {
            stream.cancel(new HttpClientClosedException());
        }
        return resilience.close();
    }

    private WebClientView scopedWebClient(AttemptScope scope, long exchangeTimeoutMs, boolean trackRequests) {
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
        return new WebClientView(
                governed, settings, () -> scope == clientScope && closeFuture.get() != null, scope, trackRequests);
    }
}
