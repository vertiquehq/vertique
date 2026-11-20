// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resilientresource;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.application.test.VertiqueAppExtension;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Proves that a resilience anchor declared on a JAX-RS server resource interface takes effect on
 * the implementor: the router resolves the resource from the graph, so the generated proxy is the
 * instance that serves the request and retries the failing body.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class FlakyResourceIT {

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(
                    new FlakyComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("jaxrs", new JsonObject().put("validationStrategy", "none"))
                    .put("resilience", new JsonObject().put("policies", new JsonObject())));

    private static WebClient client;

    @BeforeAll
    static void startClient() {
        client = WebClient.create(app.vertx());
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    @Test
    @DisplayName("the interface retry runs the implementor body until it succeeds")
    void retriesTheImplementorWhenTheInterfaceCarriesTheAnchor() throws Exception {
        Attempts attempts = app.<FlakyComponent>component().attempts();
        attempts.reset();

        HttpResponse<Buffer> response = client.get(app.httpPort(), "127.0.0.1", "/flaky")
                .send()
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyAsString()).isEqualTo("ok after 3");
        assertThat(attempts.count()).isEqualTo(3);
    }
}
