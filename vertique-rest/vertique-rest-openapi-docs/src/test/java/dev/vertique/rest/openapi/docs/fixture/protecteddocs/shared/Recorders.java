// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared;

import dev.vertique.rest.core.correlation.CorrelationIngressMiddleware;
import dev.vertique.rest.core.events.HttpRequestCompletedEvent;
import dev.vertique.rest.core.events.HttpRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import java.util.concurrent.CompletableFuture;

/**
 * The fixture's recording and counting extensions, each writing into the component's {@link
 * Observations}.
 */
public final class Recorders {

    private Recorders() {}

    /** Records every {@link CredentialRejectedEvent} and {@link AuthorizationDecisionEvent}. */
    public static final class SecurityEvents implements SecurityEventObserver {

        private final Observations observations;

        /**
         * Creates the observer.
         *
         * @param observations the component's observation hub
         */
        public SecurityEvents(Observations observations) {
            this.observations = observations;
        }

        @Override
        public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
            observations.recordSecurityEvent(event);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
            observations.recordSecurityEvent(event);
            return Future.succeededFuture();
        }
    }

    /** Records every {@link RestRequestCompletedEvent}. */
    public static final class RestCompletions implements RestRequestCompletedListener {

        private final Observations observations;

        /**
         * Creates the listener.
         *
         * @param observations the component's observation hub
         */
        public RestCompletions(Observations observations) {
            this.observations = observations;
        }

        @Override
        public void onCompleted(RestRequestCompletedEvent event) {
            observations.recordRestEvent(event);
        }
    }

    /** Records every {@link HttpRequestCompletedEvent}. */
    public static final class HttpCompletions implements HttpRequestCompletedListener {

        private final Observations observations;

        /**
         * Creates the listener.
         *
         * @param observations the component's observation hub
         */
        public HttpCompletions(Observations observations) {
            this.observations = observations;
        }

        @Override
        public void onCompleted(HttpRequestCompletedEvent event) {
            observations.recordHttpEvent(event);
        }
    }

    /** Counts every failure that reaches the error pipeline, and passes it on unchanged. */
    public static final class CountingErrorInterceptor implements ErrorInterceptor {

        private final Observations observations;

        /**
         * Creates the interceptor.
         *
         * @param observations the component's observation hub
         */
        public CountingErrorInterceptor(Observations observations) {
            this.observations = observations;
        }

        @Override
        public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
            observations.errorInterceptorHit();
            return Future.succeededFuture(throwable);
        }
    }

    /**
     * A ROOT middleware, ordered right after the request lifecycle and before correlation ingress,
     * that completes a request's barrier once the request's lifecycle closed. The lifecycle runs its
     * close tasks after every other end handler, so by then the completion listeners have run.
     */
    public static final class RequestBarrier implements Middleware {

        /** The middleware's priority: before correlation ingress, after the request lifecycle. */
        public static final int PRIORITY = CorrelationIngressMiddleware.ORDER - 1;

        private final Observations observations;

        /**
         * Creates the middleware.
         *
         * @param observations the component's observation hub
         */
        public RequestBarrier(Observations observations) {
            this.observations = observations;
        }

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void handle(RoutingContext ctx) {
            String key = ctx.request().getHeader(Observations.REQUEST_HEADER);
            CompletableFuture<Void> barrier = key != null ? observations.barrier(key) : null;
            if (barrier != null) {
                RequestContextLifecycle.fromRoutingContext(ctx).afterClose(() -> barrier.complete(null));
            }
            ctx.next();
        }
    }
}
