// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.meta.ClientInterfaceScanner;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import io.vertx.core.Future;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the reflective request factory fails fast on a {@code null} {@code @PathParam}
 * value when no {@code @DefaultValue} is set, matching the generated proxy's behaviour emitted by
 * {@code ProxyEmitter.emitPathParamCall}.
 *
 * <p>Without this guard, the path placeholder {@code {id}} was silently left unresolved by
 * {@code collectParams} and the broken URI propagated to the wire — masking the bug at the call
 * site. The generated proxy already throws on null path arguments; this test pins the reflective
 * path to the same contract.
 */
class RestClientRequestFactoryPathNullTest {

    // --- Test client interfaces ---

    /** Simple client with one {@code @PathParam} and no {@code @DefaultValue}. */
    @Path("/items/{id}")
    interface ItemsClient {
        @GET
        Future<String> get(@PathParam("id") String id);
    }

    /** Client whose path param has a {@code @DefaultValue} — null arg should substitute, not throw. */
    @Path("/items/{id}")
    interface ItemsClientWithDefault {
        @GET
        Future<String> get(@PathParam("id") @DefaultValue("default-id") String id);
    }

    /** Bean param fixture with a {@code @PathParam} field (no {@code @DefaultValue}). */
    public static class IdBean {
        @PathParam("id")
        public String id;

        public IdBean(String id) {
            this.id = id;
        }
    }

    /** Bean param fixture with a {@code @PathParam} field carrying a {@code @DefaultValue}. */
    public static class IdBeanWithDefault {
        @PathParam("id")
        @DefaultValue("default-id")
        public String id;

        public IdBeanWithDefault(String id) {
            this.id = id;
        }
    }

    /** Client where the {@code @PathParam} arrives via a {@code @BeanParam} field. */
    @Path("/items/{id}")
    interface BeanItemsClient {
        @GET
        Future<String> get(@BeanParam IdBean bean);
    }

    /** Client where the {@code @BeanParam} field has a {@code @DefaultValue}. */
    @Path("/items/{id}")
    interface BeanItemsClientWithDefault {
        @GET
        Future<String> get(@BeanParam IdBeanWithDefault bean);
    }

    // --- Fields ---

    private RestClientRequestFactory factory;
    private ClientMethodMeta itemsMeta;
    private ClientMethodMeta itemsWithDefaultMeta;
    private ClientMethodMeta beanItemsMeta;
    private ClientMethodMeta beanItemsWithDefaultMeta;

    @BeforeEach
    void setUp() {
        factory = new RestClientRequestFactory(DatabindCodec.mapper());

        Map<Method, ClientMethodMeta> itemsScanned = ClientInterfaceScanner.scan(ItemsClient.class);
        itemsMeta = itemsScanned.values().iterator().next();

        Map<Method, ClientMethodMeta> itemsDefScanned = ClientInterfaceScanner.scan(ItemsClientWithDefault.class);
        itemsWithDefaultMeta = itemsDefScanned.values().iterator().next();

        Map<Method, ClientMethodMeta> beanScanned = ClientInterfaceScanner.scan(BeanItemsClient.class);
        beanItemsMeta = beanScanned.values().iterator().next();

        Map<Method, ClientMethodMeta> beanDefScanned = ClientInterfaceScanner.scan(BeanItemsClientWithDefault.class);
        beanItemsWithDefaultMeta = beanDefScanned.values().iterator().next();
    }

    // --- Null @PathParam without @DefaultValue ---

    @Test
    @DisplayName("buildPath throws RestClientException when @PathParam is null and has no @DefaultValue")
    void buildPath_throwsOnNullPathParam() {
        assertThatThrownBy(() -> factory.buildPath(itemsMeta, new Object[] {null}))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("path param 'id'")
                .hasMessageContaining("null")
                .hasMessageContaining("@DefaultValue");
    }

    @Test
    @DisplayName("buildRequestBuilder throws RestClientException when @PathParam is null and has no @DefaultValue")
    void buildRequestBuilder_throwsOnNullPathParam() {
        assertThatThrownBy(() -> factory.buildRequestBuilder(itemsMeta, new Object[] {null}))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("path param 'id'");
    }

    // --- Null @PathParam with @DefaultValue ---

    @Test
    @DisplayName("buildPath substitutes @DefaultValue when @PathParam is null")
    void buildPath_substitutesDefaultValue() {
        String path = factory.buildPath(itemsWithDefaultMeta, new Object[] {null});
        assertThat(path).isEqualTo("/items/default-id");
    }

    // --- @BeanParam @PathParam parity (Codex round-7 finding) ---

    @Test
    @DisplayName("buildPath throws when a @BeanParam @PathParam field is null and has no @DefaultValue")
    void buildPath_throwsOnNullBeanFieldPathParam() {
        IdBean bean = new IdBean(null);
        assertThatThrownBy(() -> factory.buildPath(beanItemsMeta, new Object[] {bean}))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("path param 'id'")
                .hasMessageContaining("@DefaultValue");
    }

    @Test
    @DisplayName("buildPath substitutes @DefaultValue when a @BeanParam @PathParam field is null")
    void buildPath_substitutesDefaultValueForBeanField() {
        IdBeanWithDefault bean = new IdBeanWithDefault(null);
        String path = factory.buildPath(beanItemsWithDefaultMeta, new Object[] {bean});
        assertThat(path).isEqualTo("/items/default-id");
    }
}
