// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A client interface that binds the type variables of a generic super-interface dispatches the
 * inherited operations, whether it is called through its own type or through the super-interface.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class RestClientGenericInheritanceIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    record Widget(String name) {}

    interface Crud<T, ID> {
        @GET
        @Path("/{id}")
        Future<T> get(@PathParam("id") ID id);

        @GET
        Future<List<T>> list();

        @POST
        Future<T> create(T body);

        @DELETE
        @Path("/{id}")
        Future<Void> delete(@PathParam("id") ID id);
    }

    @Path("/widgets")
    interface WidgetClient extends Crud<Widget, String> {
        @Override
        Future<Void> delete(String id);
    }

    private WidgetClient client(Vertx vertx) {
        return new RestClientBuilder(vertx)
                .baseUrl("http://127.0.0.1:" + wireMock.getPort())
                .build(WidgetClient.class);
    }

    @Test
    @DisplayName("inherited operations decode the bound response type and bind the bound parameter types")
    void inheritedOperationsDispatch(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/widgets/7")).willReturn(okJson("{\"name\":\"seven\"}")));
        wireMock.stubFor(get(urlEqualTo("/widgets")).willReturn(okJson("[{\"name\":\"a\"},{\"name\":\"b\"}]")));
        wireMock.stubFor(post(urlEqualTo("/widgets"))
                .withRequestBody(equalToJson("{\"name\":\"new\"}"))
                .willReturn(okJson("{\"name\":\"new\"}")));
        WidgetClient client = client(vertx);

        client.get("7")
                .compose(widget -> {
                    assertThat(widget).isEqualTo(new Widget("seven"));
                    return client.list();
                })
                .compose(widgets -> {
                    assertThat(widgets).containsExactly(new Widget("a"), new Widget("b"));
                    return client.create(new Widget("new"));
                })
                .onComplete(ctx.succeeding(widget -> ctx.verify(() -> {
                    assertThat(widget).isEqualTo(new Widget("new"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a redeclared operation dispatches through the client type and through the super-interface")
    void redeclaredOperationDispatchesThroughBothTypes(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(delete(urlEqualTo("/widgets/1")).willReturn(aResponse().withStatus(204)));
        wireMock.stubFor(delete(urlEqualTo("/widgets/2")).willReturn(aResponse().withStatus(204)));
        WidgetClient client = client(vertx);
        Crud<Widget, String> viaSuper = client;

        client.delete("1")
                .compose(v -> viaSuper.delete("2"))
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    wireMock.verify(1, deleteRequestedFor(urlEqualTo("/widgets/1")));
                    wireMock.verify(1, deleteRequestedFor(urlEqualTo("/widgets/2")));
                    ctx.completeNow();
                })));
    }
}
