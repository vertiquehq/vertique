// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.support;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.Nullable;
import java.time.Duration;

/** Request helpers for reading documents from a deployed component on the loopback interface. */
public final class DocumentRequests {

    private DocumentRequests() {}

    /**
     * What a document request returned.
     *
     * @param status the HTTP status code
     * @param body the response body, empty when the response had none
     * @param etag the {@code ETag} header, or {@code null} when absent
     * @param contentType the {@code Content-Type} header, or {@code null} when absent
     * @param headers every response header
     */
    public record Answer(
            int status,
            byte[] body,
            @Nullable String etag,
            @Nullable String contentType,
            MultiMap headers) {}

    /**
     * Sends a {@code GET} to {@code 127.0.0.1} and waits up to {@code bound} for the answer.
     *
     * @param client the client to send with
     * @param port the port to connect to
     * @param path the request path, including any query
     * @param bearerToken a bearer token to present, or {@code null} to send no credentials
     * @param bound the longest the answer is awaited
     * @return the answer
     * @throws Exception when the request fails or times out
     */
    public static Answer get(WebClient client, int port, String path, @Nullable String bearerToken, Duration bound)
            throws Exception {
        HttpRequest<Buffer> request = client.get(port, "127.0.0.1", path);
        if (bearerToken != null) {
            request.putHeader("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<Buffer> response = Futures.await(request.send(), bound);
        Buffer body = response.body();
        return new Answer(
                response.statusCode(),
                body == null ? new byte[0] : body.getBytes(),
                response.getHeader("ETag"),
                response.getHeader("Content-Type"),
                response.headers());
    }

    /**
     * Creates a client whose default host is {@code 127.0.0.1} and whose connections are not kept
     * alive, so each request opens its own connection. The caller closes the client.
     *
     * @param vertx the Vert.x instance
     * @return the client
     */
    public static WebClient separateConnectionsClient(Vertx vertx) {
        return WebClient.create(
                vertx, new WebClientOptions().setDefaultHost("127.0.0.1").setKeepAlive(false));
    }
}
