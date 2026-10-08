// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.rest.client.exception.RestClientConfigurationException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two client interfaces that resolve to the same client name would share one {@code restClient.{name}.*}
 * configuration and every per-client binding keyed on that name. The factory refuses the second one
 * at build time instead.
 */
@DisplayName("RestClientFactory client-name collision")
class RestClientNameCollisionTest {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close();
    }

    /** Simple name {@code Orders}, owned by {@code BillingApi}. */
    static final class BillingApi {
        @Path("/orders")
        interface Orders {
            @GET
            Future<String> list();
        }
    }

    /** Simple name {@code Orders} again, owned by {@code ShippingApi}. */
    static final class ShippingApi {
        @Path("/orders")
        interface Orders {
            @GET
            Future<String> list();
        }
    }

    @Path("/orders")
    @RestClient(name = "shipping-orders")
    interface NamedOrders {
        @GET
        Future<String> list();
    }

    @Path("/orders")
    @RestClient(name = "shared-orders")
    interface SharedOrdersRead {
        @GET
        Future<String> list();
    }

    @Path("/orders")
    @RestClient(name = "shared-orders")
    interface SharedOrdersWrite {
        @GET
        Future<String> list();
    }

    /** Writes the explicit name {@code Orders}, equal to the simple name {@link BillingApi.Orders} derives. */
    @Path("/orders")
    @RestClient(name = "Orders")
    interface ExplicitOrders {
        @GET
        Future<String> list();
    }

    private static RestClientFactory factory() {
        return new RestClientFactory(vertx, Set.of(), Map.of(), null);
    }

    @Test
    @DisplayName("a second interface resolving to the same simple name is refused, naming both")
    void sameSimpleNameDifferentInterfaceIsRefused() {
        RestClientFactory factory = factory();
        factory.builder().baseUrl("http://localhost:1").build(BillingApi.Orders.class);

        assertThatThrownBy(() -> factory.builder().baseUrl("http://localhost:1").build(ShippingApi.Orders.class))
                .isInstanceOf(RestClientConfigurationException.class)
                .hasMessageContaining(BillingApi.Orders.class.getName())
                .hasMessageContaining(ShippingApi.Orders.class.getName())
                .hasMessageContaining("'Orders'")
                .hasMessageContaining("@RestClient(name");
    }

    @Test
    @DisplayName("the collision is detected across builders created by one factory")
    void collisionIsFactoryScoped() {
        RestClientFactory factory = factory();
        RestClientBuilder first = factory.builder().baseUrl("http://localhost:1");
        RestClientBuilder second = factory.builder().baseUrl("http://localhost:1");
        first.build(BillingApi.Orders.class);

        assertThatThrownBy(() -> second.build(ShippingApi.Orders.class))
                .isInstanceOf(RestClientConfigurationException.class);
    }

    @Test
    @DisplayName("rebuilding the same interface is legal")
    void sameInterfaceTwiceIsAccepted() {
        RestClientFactory factory = factory();

        assertThatCode(() -> {
                    factory.builder().baseUrl("http://localhost:1").build(BillingApi.Orders.class);
                    factory.builder().baseUrl("http://localhost:1").build(BillingApi.Orders.class);
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an explicit @RestClient(name) disambiguates")
    void explicitNameAvoidsTheCollision() {
        RestClientFactory factory = factory();
        factory.builder().baseUrl("http://localhost:1").build(BillingApi.Orders.class);

        assertThat(factory.builder().baseUrl("http://localhost:1").build(NamedOrders.class))
                .isNotNull();
    }

    @Test
    @DisplayName("several interfaces may deliberately share one explicit name")
    void sharedExplicitNameIsAccepted() {
        RestClientFactory factory = factory();
        factory.builder().baseUrl("http://localhost:1").build(SharedOrdersRead.class);

        assertThatCode(() -> factory.builder().baseUrl("http://localhost:1").build(SharedOrdersWrite.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an explicit name equal to another interface's simple name is refused, in either order")
    void explicitNameEqualToAFallbackNameIsRefused() {
        RestClientFactory fallbackFirst = factory();
        fallbackFirst.builder().baseUrl("http://localhost:1").build(BillingApi.Orders.class);
        assertThatThrownBy(() ->
                        fallbackFirst.builder().baseUrl("http://localhost:1").build(ExplicitOrders.class))
                .isInstanceOf(RestClientConfigurationException.class)
                .hasMessageContaining("'Orders'");

        RestClientFactory explicitFirst = factory();
        explicitFirst.builder().baseUrl("http://localhost:1").build(ExplicitOrders.class);
        assertThatThrownBy(() ->
                        explicitFirst.builder().baseUrl("http://localhost:1").build(BillingApi.Orders.class))
                .isInstanceOf(RestClientConfigurationException.class);
    }

    @Test
    @DisplayName("separate factories keep separate registries")
    void separateFactoriesDoNotInterfere() {
        factory().builder().baseUrl("http://localhost:1").build(BillingApi.Orders.class);

        assertThatCode(() -> factory().builder().baseUrl("http://localhost:1").build(ShippingApi.Orders.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a standalone builder performs no cross-build check")
    void standaloneBuilderIsUnchecked() {
        RestClientBuilder builder = new RestClientBuilder(vertx).baseUrl("http://localhost:1");
        builder.build(BillingApi.Orders.class);

        assertThatCode(() -> builder.build(ShippingApi.Orders.class)).doesNotThrowAnyException();
    }
}
