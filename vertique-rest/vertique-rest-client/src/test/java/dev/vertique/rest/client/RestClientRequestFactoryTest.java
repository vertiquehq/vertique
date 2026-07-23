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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestClientRequestFactory} URL-related methods: {@code extractUrlParam} and
 * {@code buildUrlWithQueryParams}.
 *
 * <p>Verifies runtime validation of {@code @Url} URI arguments (null, relative, non-HTTP scheme,
 * fragment, opaque, missing host) and query-parameter merge logic (existing query, no query, bare
 * {@code ?}, port preservation, pre-encoded paths, authority-only URIs).
 */
class RestClientRequestFactoryTest {

    // --- Test client interfaces ---

    interface UrlOnlyClient {
        @GET
        Future<String> call(@Url URI url);
    }

    interface UrlWithQueryClient {
        @GET
        Future<String> call(@Url URI url, @QueryParam("b") String b);
    }

    @Path("/items/{id}")
    interface NoUrlClient {
        @GET
        Future<String> get(@PathParam("id") String id);
    }

    // --- Fields ---

    private RestClientRequestFactory factory;
    private ClientMethodMeta urlOnlyMeta;
    private ClientMethodMeta urlWithQueryMeta;
    private ClientMethodMeta noUrlMeta;

    @BeforeEach
    void setUp() {
        factory = new RestClientRequestFactory(DatabindCodec.mapper());

        Map<Method, ClientMethodMeta> urlOnlyScanned = ClientInterfaceScanner.scan(UrlOnlyClient.class);
        urlOnlyMeta = urlOnlyScanned.values().iterator().next();

        Map<Method, ClientMethodMeta> urlWithQueryScanned = ClientInterfaceScanner.scan(UrlWithQueryClient.class);
        urlWithQueryMeta = urlWithQueryScanned.values().iterator().next();

        Map<Method, ClientMethodMeta> noUrlScanned = ClientInterfaceScanner.scan(NoUrlClient.class);
        noUrlMeta = noUrlScanned.values().iterator().next();
    }

    // --- extractUrlParam: happy path ---

    @Test
    @DisplayName("extractUrlParam returns the absolute URI when argument is valid")
    void extractUrlParam_returnsAbsoluteUri() {
        URI uri = URI.create("https://example.com/path");
        URI result = factory.extractUrlParam(urlOnlyMeta, new Object[] {uri});
        assertThat(result).isEqualTo(uri);
    }

    @Test
    @DisplayName("extractUrlParam returns null when the method has no @Url parameter")
    void extractUrlParam_returnsNullForNoUrlParam() {
        URI result = factory.extractUrlParam(noUrlMeta, new Object[] {"42"});
        assertThat(result).isNull();
    }

    // --- extractUrlParam: validation errors ---

    @Test
    @DisplayName("extractUrlParam throws RestClientException when argument is null")
    void extractUrlParam_throwsOnNull() {
        assertThatThrownBy(() -> factory.extractUrlParam(urlOnlyMeta, new Object[] {null}))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("extractUrlParam throws RestClientException for a relative URI")
    void extractUrlParam_throwsOnRelativeUri() {
        assertThatThrownBy(() -> factory.extractUrlParam(urlOnlyMeta, new Object[] {URI.create("/relative")}))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("extractUrlParam throws RestClientException for a non-HTTP scheme (ftp://)")
    void extractUrlParam_throwsOnNonHttpScheme() {
        assertThatThrownBy(() -> factory.extractUrlParam(urlOnlyMeta, new Object[] {URI.create("ftp://example.com")}))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("extractUrlParam throws RestClientException when URI contains a fragment")
    void extractUrlParam_throwsOnFragment() {
        assertThatThrownBy(() -> factory.extractUrlParam(
                        urlOnlyMeta, new Object[] {URI.create("https://example.com/path#frag")}))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("extractUrlParam throws RestClientException for an opaque URI (mailto:)")
    void extractUrlParam_throwsOnOpaqueUri() {
        assertThatThrownBy(() ->
                        factory.extractUrlParam(urlOnlyMeta, new Object[] {URI.create("mailto:user@example.com")}))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("extractUrlParam throws RestClientException when host is missing (http://:8080/path)")
    void extractUrlParam_throwsOnMissingHost() {
        assertThatThrownBy(() -> factory.extractUrlParam(urlOnlyMeta, new Object[] {URI.create("http://:8080/path")}))
                .isInstanceOf(RestClientException.class);
    }

    // --- buildUrlWithQueryParams ---

    @Test
    @DisplayName("buildUrlWithQueryParams returns URI string unchanged when no query params are present")
    void buildUrlWithQueryParams_noExtraQuery() {
        URI uri = URI.create("https://host/path");
        String result = factory.buildUrlWithQueryParams(uri, urlOnlyMeta, new Object[] {uri});
        assertThat(result).isEqualTo("https://host/path");
    }

    @Test
    @DisplayName("buildUrlWithQueryParams appends @QueryParam to existing query string")
    void buildUrlWithQueryParams_mergesWithExistingQuery() {
        URI uri = URI.create("https://host/path?a=1");
        String result = factory.buildUrlWithQueryParams(uri, urlWithQueryMeta, new Object[] {uri, "2"});
        assertThat(result).isEqualTo("https://host/path?a=1&b=2");
    }

    @Test
    @DisplayName("buildUrlWithQueryParams adds @QueryParam when URI has no existing query")
    void buildUrlWithQueryParams_addsQueryWhenNoneExists() {
        URI uri = URI.create("https://host/path");
        String result = factory.buildUrlWithQueryParams(uri, urlWithQueryMeta, new Object[] {uri, "2"});
        assertThat(result).isEqualTo("https://host/path?b=2");
    }

    @Test
    @DisplayName("buildUrlWithQueryParams treats a bare trailing '?' as no existing query")
    void buildUrlWithQueryParams_emptyQueryTreatedAsNoQuery() {
        URI uri = URI.create("https://host/path?");
        String result = factory.buildUrlWithQueryParams(uri, urlOnlyMeta, new Object[] {uri});
        assertThat(result).isEqualTo("https://host/path");
    }

    @Test
    @DisplayName("buildUrlWithQueryParams preserves the port in the reconstructed URI")
    void buildUrlWithQueryParams_preservesPort() {
        URI uri = URI.create("https://host:9443/path");
        String result = factory.buildUrlWithQueryParams(uri, urlOnlyMeta, new Object[] {uri});
        assertThat(result).isEqualTo("https://host:9443/path");
    }

    @Test
    @DisplayName("buildUrlWithQueryParams does not double-encode a pre-encoded path")
    void buildUrlWithQueryParams_noDoubleEncoding() {
        URI uri = URI.create("https://host/path%20with%20spaces");
        String result = factory.buildUrlWithQueryParams(uri, urlOnlyMeta, new Object[] {uri});
        assertThat(result).contains("/path%20with%20spaces");
        assertThat(result).doesNotContain("%2520");
    }

    @Test
    @DisplayName("buildUrlWithQueryParams handles an authority-only URI (no path)")
    void buildUrlWithQueryParams_emptyPathHandled() {
        URI uri = URI.create("https://host:8080");
        String result = factory.buildUrlWithQueryParams(uri, urlOnlyMeta, new Object[] {uri});
        assertThat(result).isEqualTo("https://host:8080");
    }

    @Test
    @DisplayName("buildUrlWithQueryParams inserts '/' before '?' for authority-only URI with query params")
    void buildUrlWithQueryParams_authorityOnlyWithQueryInsertsSlash() {
        URI uri = URI.create("https://api.example.com");
        String result = factory.buildUrlWithQueryParams(uri, urlWithQueryMeta, new Object[] {uri, "2"});
        assertThat(result).isEqualTo("https://api.example.com/?b=2");
    }
}
