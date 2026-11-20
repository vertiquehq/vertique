// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.security.channel.ChannelIdentityManager;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph proof that {@link RequestOriginConfig} is the one replaceable origin binding in
 * {@link AuthModule}: an application-supplied configuration reaches the {@link
 * RequestOriginCapturer}, the default applies when there is none, and the credential-rejection
 * reporter and channel identity manager stay plainly injectable.
 */
class RequestOriginConfigReplacementWiringTest {

    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    /** Supplies the application's own trusted-proxy policy. */
    @Module
    static final class TrustedProxyModule {

        @Provides
        @Singleton
        static RequestOriginConfig requestOriginConfig() {
            return new RequestOriginConfig(Set.of("10.0.0.0/8"), 8, false, false);
        }
    }

    @Singleton
    @Component(modules = {AuthModule.class, SecurityModule.class, ContextRuntimeModule.class})
    interface DefaultGraph {

        RequestOriginCapturer capturer();

        CredentialRejectionReporter credentialRejectionReporter();

        ChannelIdentityManager channelIdentityManager();

        @Component.Factory
        interface Factory {
            DefaultGraph create(@BindsInstance Vertx vertx);
        }
    }

    @Singleton
    @Component(modules = {AuthModule.class, SecurityModule.class, ContextRuntimeModule.class, TrustedProxyModule.class})
    interface ReplacedGraph {

        RequestOriginCapturer capturer();

        CredentialRejectionReporter credentialRejectionReporter();

        ChannelIdentityManager channelIdentityManager();

        @Component.Factory
        interface Factory {
            ReplacedGraph create(@BindsInstance Vertx vertx);
        }
    }

    private static HttpServerRequest requestFrom(String peer, String forwardedFor) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        SocketAddress remote = mock(SocketAddress.class);
        HttpConnection connection = mock(HttpConnection.class);
        when(remote.host()).thenReturn(peer);
        when(remote.port()).thenReturn(40000);
        when(request.remoteAddress()).thenReturn(remote);
        when(request.scheme()).thenReturn("http");
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        when(request.connection()).thenReturn(connection);
        return request;
    }

    @Test
    @DisplayName(
            "an application-supplied RequestOriginConfig reaches the origin capturer and its trusted proxies apply")
    void appliesApplicationSuppliedOriginConfig() {
        ReplacedGraph graph = DaggerRequestOriginConfigReplacementWiringTest_ReplacedGraph.factory()
                .create(VERTX);

        RequestOrigin origin = graph.capturer().capture(requestFrom("10.1.2.3", "203.0.113.9"));

        assertEquals("203.0.113.9", origin.clientIp(), "the trusted proxy's forwarded chain must be honoured");
        assertNotNull(graph.credentialRejectionReporter());
        assertNotNull(graph.channelIdentityManager());
    }

    @Test
    @DisplayName("without an application binding the default origin config applies and trusts no proxy")
    void appliesDefaultOriginConfigWhenNoneIsBound() {
        DefaultGraph graph = DaggerRequestOriginConfigReplacementWiringTest_DefaultGraph.factory()
                .create(VERTX);

        RequestOrigin origin = graph.capturer().capture(requestFrom("10.1.2.3", "203.0.113.9"));

        assertEquals("10.1.2.3", origin.clientIp(), "no proxy is trusted by default, so the peer address is used");
        assertNotNull(graph.credentialRejectionReporter());
        assertNotNull(graph.channelIdentityManager());
    }
}
