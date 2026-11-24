// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.http.client.exception.HttpClientClosedException;
import io.vertx.core.Vertx;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpClientFactoryTest {

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void closeVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("client(name) after stop throws HttpClientClosedException even for a name already built")
    void clientAfterStopThrowsForABuiltName() throws Exception {
        HttpClientFactory factory = new HttpClientFactory(vertx, Set.of(), Map.of());
        HttpClient first = factory.client("a");
        assertThat(factory.client("a")).isSameAs(first);

        factory.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> factory.client("a")).isExactlyInstanceOf(HttpClientClosedException.class);
        assertThatThrownBy(() -> factory.client("b")).isExactlyInstanceOf(HttpClientClosedException.class);
    }
}
