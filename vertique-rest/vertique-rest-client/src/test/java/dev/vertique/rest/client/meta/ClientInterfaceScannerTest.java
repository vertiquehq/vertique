// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.resilience.BackoffStrategy;
import dev.vertique.core.resilience.Retry;
import dev.vertique.rest.client.HttpClientResponse;
import dev.vertique.rest.client.Url;
import io.vertx.core.Future;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClientInterfaceScanner}.
 *
 * <p>Verifies that JAX-RS-annotated interfaces are scanned correctly, producing
 * {@link ClientMethodMeta} descriptors with the right HTTP method, path template,
 * parameter types, and media type resolution.
 */
class ClientInterfaceScannerTest {

    // --- Test DTOs ---

    /** Simple DTO used across multiple test interfaces. */
    record Item(String name) {}

    // --- Test Interfaces ---

    @Path("/items")
    interface GetListClient {
        @GET
        @Path("/")
        Future<List<Item>> list();
    }

    @Path("/items")
    interface PostBodyClient {
        @POST
        @Path("/")
        Future<Item> create(Item body);
    }

    @Path("/items")
    interface PathAndQueryClient {
        @GET
        @Path("/{id}")
        Future<Item> get(@PathParam("id") String id, @QueryParam("q") String q);
    }

    @Path("/items")
    interface HeaderAndCookieClient {
        @GET
        @Path("/{id}")
        Future<Item> get(
                @PathParam("id") String id,
                @HeaderParam("X-Token") String token,
                @CookieParam("session") String session);
    }

    @Path("/items")
    interface BeanParamClient {
        @GET
        @Path("/search")
        Future<List<Item>> search(@BeanParam SearchParams params);
    }

    /** Regular class @BeanParam. */
    static class SearchParams {
        @QueryParam("q")
        String query;

        @QueryParam("page")
        int page;
    }

    @Path("/items")
    interface RecordBeanParamClient {
        @GET
        @Path("/search")
        Future<List<Item>> search(@BeanParam RecordParams params);
    }

    /** Record @BeanParam — annotations on constructor parameters become record-component annotations. */
    record RecordParams(
            @QueryParam("q") String query,
            @QueryParam("page") int page) {}

    @Consumes("application/xml")
    @Produces("application/xml")
    @Path("/items")
    interface ClassLevelMediaTypeClient {
        @GET
        @Path("/{id}")
        Future<Item> getXml(@PathParam("id") String id);

        @POST
        @Path("/")
        @Consumes("text/plain")
        @Produces("text/html")
        Future<Item> createText(String body);
    }

    @Path("/items")
    interface VoidReturnClient {
        @DELETE
        @Path("/{id}")
        Future<Void> delete(@PathParam("id") String id);
    }

    @Path("/items")
    interface RawResponseClient {
        @GET
        @Path("/{id}")
        Future<HttpClientResponse> getRaw(@PathParam("id") String id);
    }

    @Path("/items")
    interface GenericListClient {
        @GET
        @Path("/")
        Future<List<Item>> list();
    }

    @Path("/items")
    interface DefaultValueClient {
        @GET
        @Path("/")
        Future<List<Item>> list(@QueryParam("page") @DefaultValue("0") int page);
    }

    @Path("/base")
    interface ClassAndMethodPathClient {
        @GET
        @Path("/resource")
        Future<Item> get();
    }

    interface DoubleSlashPathClient {
        @GET
        @Path("//items//search")
        Future<Item> search();
    }

    // --- @Url test interfaces ---

    interface UrlGetClient {
        @GET
        Future<Item> call(@Url URI url);
    }

    interface UrlWithQueryClient {
        @GET
        Future<Item> call(@Url URI url, @QueryParam("q") String q);
    }

    interface UrlWithHeaderClient {
        @GET
        Future<Item> call(@Url URI url, @HeaderParam("X-Token") String token);
    }

    interface UrlWithCookieClient {
        @GET
        Future<Item> call(@Url URI url, @CookieParam("session") String session);
    }

    interface UrlWithBodyClient {
        @POST
        Future<Item> call(@Url URI url, Item body);
    }

    // Note: BeanParam contains only @QueryParam fields (no @PathParam), so this is allowed
    interface UrlWithBeanQueryClient {
        @GET
        Future<List<Item>> call(@Url URI url, @BeanParam SearchParams params);
    }

    // --- @Url validation error interfaces ---

    interface UrlWrongTypeClient {
        @GET
        Future<Item> call(@Url String url);
    }

    interface DoubleUrlClient {
        @GET
        Future<Item> call(@Url URI url1, @Url URI url2);
    }

    interface UrlWithQueryOnSameParamClient {
        @GET
        Future<Item> call(@Url @QueryParam("q") URI url);
    }

    interface UrlWithPathOnSameParamClient {
        @GET
        Future<Item> call(@Url @PathParam("id") URI url);
    }

    interface UrlWithHeaderOnSameParamClient {
        @GET
        Future<Item> call(@Url @HeaderParam("X-Token") URI url);
    }

    interface UrlWithCookieOnSameParamClient {
        @GET
        Future<Item> call(@Url @CookieParam("session") URI url);
    }

    interface UrlWithBeanOnSameParamClient {
        @GET
        Future<Item> call(@Url @BeanParam URI url);
    }

    interface UrlWithFormParamOnSameClient {
        @GET
        Future<Item> call(@Url @FormParam("field") URI url);
    }

    interface UrlWithMatrixParamOnSameClient {
        @GET
        Future<Item> call(@Url @MatrixParam("m") URI url);
    }

    interface UrlWithDefaultValueClient {
        @GET
        Future<Item> call(@Url @DefaultValue("http://example.com") URI url);
    }

    interface UrlWithMethodPathClient {
        @GET
        @Path("/foo")
        Future<Item> call(@Url URI url);
    }

    @Path("/base")
    interface UrlWithInterfacePathClient {
        @GET
        Future<Item> call(@Url URI url);
    }

    interface UrlWithPathParamOnOtherClient {
        @GET
        Future<Item> call(@Url URI url, @PathParam("id") String id);
    }

    // BeanParam with nested @PathParam — should be rejected
    interface UrlWithBeanPathParamClient {
        @GET
        Future<Item> call(@Url URI url, @BeanParam PathBeanParams params);
    }

    static class PathBeanParams {

        @PathParam("id")
        String id;

        @QueryParam("q")
        String query;
    }

    // --- @Retry test interfaces ---

    /** No-delay backoff for use in retry annotation tests. */
    static class NoDelayBackoff implements BackoffStrategy {

        /** Creates a new instance. */
        public NoDelayBackoff() {}

        /**
         * Returns zero for every retry count.
         *
         * @param retryCount unused
         * @return {@code 0L}
         */
        @Override
        public long delay(int retryCount) {
            return 0L;
        }
    }

    @Path("/retry")
    interface MethodLevelRetryClient {

        /** Method with explicit {@code @Retry(maxRetries=5)}. */
        @GET
        @Path("/with-retry")
        @Retry(maxRetries = 5, backoff = NoDelayBackoff.class)
        Future<Item> withRetry();

        /** Method without any {@code @Retry} annotation. */
        @GET
        @Path("/no-retry")
        Future<Item> noRetry();
    }

    @Retry(maxRetries = 3, backoff = NoDelayBackoff.class)
    @Path("/interface-retry")
    interface InterfaceLevelRetryClient {

        /** Method inherits interface-level {@code @Retry}. */
        @GET
        @Path("/inherited")
        Future<Item> inherited();

        /** Method-level annotation overrides the interface-level default. */
        @GET
        @Path("/override")
        @Retry(maxRetries = 1, backoff = NoDelayBackoff.class)
        Future<Item> overrideRetry();
    }

    // --- Phase 4.1 metadata-composition test interfaces ---

    @Path("/comp")
    interface QueryUuidClient {
        @GET
        @Path("/")
        Future<Item> doGet(@QueryParam("id") java.util.UUID id);
    }

    @Path("/comp")
    interface QueryListUuidClient {
        @GET
        @Path("/")
        Future<Item> doGet(@QueryParam("ids") List<java.util.UUID> ids);
    }

    @Path("/comp")
    interface AnnotatedParamClient {
        @GET
        @Path("/")
        Future<Item> doGet(@QueryParam("id") @DefaultValue("x") String id);
    }

    @Path("/comp")
    interface ResponseGenericClient {
        @GET
        @Path("/")
        Future<List<Item>> list();
    }

    // --- Error cases ---

    class NotAnInterface {}

    @Path("/items")
    interface NonFutureReturnClient {
        @GET
        @Path("/{id}")
        Item get(@PathParam("id") String id);
    }

    // --- Tests ---

    @Test
    @DisplayName("GET method is detected with correct httpMethod and path")
    void detectsGetMethodWithPath() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(GetListClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.httpMethod()).isEqualTo("GET");
        assertThat(meta.pathTemplate()).isEqualTo("/items");
    }

    @Test
    @DisplayName("POST method detects unannotated parameter as BODY")
    void detectsPostWithBody() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(PostBodyClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.httpMethod()).isEqualTo("POST");

        List<ClientParamMeta> params = meta.params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isEqualTo(ClientParamMeta.ParamSource.BODY);
        assertThat(params.get(0).name()).isNull();
        assertThat(params.get(0).type()).isEqualTo(Item.class);
    }

    @Test
    @DisplayName("@PathParam and @QueryParam are detected with correct source and name")
    void detectsPathAndQueryParams() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(PathAndQueryClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.pathTemplate()).isEqualTo("/items/{id}");

        List<ClientParamMeta> params = meta.params();
        assertThat(params).hasSize(2);

        ClientParamMeta pathParam = params.get(0);
        assertThat(pathParam.source()).isEqualTo(ClientParamMeta.ParamSource.PATH);
        assertThat(pathParam.name()).isEqualTo("id");

        ClientParamMeta queryParam = params.get(1);
        assertThat(queryParam.source()).isEqualTo(ClientParamMeta.ParamSource.QUERY);
        assertThat(queryParam.name()).isEqualTo("q");
    }

    @Test
    @DisplayName("@HeaderParam and @CookieParam are detected with correct source and name")
    void detectsHeaderAndCookieParams() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(HeaderAndCookieClient.class);

        assertThat(metas).hasSize(1);
        List<ClientParamMeta> params = metas.values().iterator().next().params();
        assertThat(params).hasSize(3);

        ClientParamMeta headerParam = params.get(1);
        assertThat(headerParam.source()).isEqualTo(ClientParamMeta.ParamSource.HEADER);
        assertThat(headerParam.name()).isEqualTo("X-Token");

        ClientParamMeta cookieParam = params.get(2);
        assertThat(cookieParam.source()).isEqualTo(ClientParamMeta.ParamSource.COOKIE);
        assertThat(cookieParam.name()).isEqualTo("session");
    }

    @Test
    @DisplayName("@BeanParam class is expanded into sub-parameters")
    void expandsBeanParamClass() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(BeanParamClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        List<ClientParamMeta> params = meta.params();
        assertThat(params).hasSize(1);

        ClientParamMeta beanParam = params.get(0);
        assertThat(beanParam.source()).isEqualTo(ClientParamMeta.ParamSource.BEAN_PARAM);

        List<ClientParamMeta> beanFields = beanParam.beanFields();
        assertThat(beanFields).hasSize(2);
        assertThat(beanFields).extracting(ClientParamMeta::source).containsOnly(ClientParamMeta.ParamSource.QUERY);
        assertThat(beanFields).extracting(ClientParamMeta::name).containsExactlyInAnyOrder("q", "page");
    }

    @Test
    @DisplayName("Record @BeanParam is expanded into sub-parameters from record components")
    void expandsRecordBeanParam() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(RecordBeanParamClient.class);

        assertThat(metas).hasSize(1);
        ClientParamMeta beanParam = metas.values().iterator().next().params().get(0);
        assertThat(beanParam.source()).isEqualTo(ClientParamMeta.ParamSource.BEAN_PARAM);

        List<ClientParamMeta> beanFields = beanParam.beanFields();
        assertThat(beanFields).hasSize(2);
        assertThat(beanFields).extracting(ClientParamMeta::source).containsOnly(ClientParamMeta.ParamSource.QUERY);
        assertThat(beanFields).extracting(ClientParamMeta::name).containsExactlyInAnyOrder("q", "page");
    }

    @Test
    @DisplayName("Class-level @Consumes/@Produces are used when method has none")
    void classLevelMediaTypeIsUsedWhenMethodHasNone() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(ClassLevelMediaTypeClient.class);
        Map<String, ClientMethodMeta> byName = indexByMethodName(metas);

        ClientMethodMeta getXml = byName.get("getXml");
        assertThat(getXml.consumesMediaType()).isEqualTo("application/xml");
        assertThat(getXml.producesMediaType()).isEqualTo("application/xml");
    }

    @Test
    @DisplayName("Method-level @Consumes/@Produces override class-level annotations")
    void methodLevelMediaTypeOverridesClassLevel() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(ClassLevelMediaTypeClient.class);
        Map<String, ClientMethodMeta> byName = indexByMethodName(metas);

        ClientMethodMeta createText = byName.get("createText");
        assertThat(createText.consumesMediaType()).isEqualTo("text/plain");
        assertThat(createText.producesMediaType()).isEqualTo("text/html");
    }

    @Test
    @DisplayName("Future<Void> return type sets returnsVoid=true")
    void futureVoidSetsReturnsVoid() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(VoidReturnClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.returnsVoid()).isTrue();
        assertThat(meta.returnsRawResponse()).isFalse();
    }

    @Test
    @DisplayName("Future<HttpClientResponse> return type sets returnsRawResponse=true")
    void futureHttpClientResponseSetsReturnsRawResponse() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(RawResponseClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.returnsRawResponse()).isTrue();
        assertThat(meta.returnsVoid()).isFalse();
        assertThat(meta.rawResponseType()).isEqualTo(HttpClientResponse.class);
    }

    @Test
    @DisplayName("Future<List<Item>> captures the full ParameterizedType as responseType")
    void genericListTypeCapturesParameterizedType() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(GenericListClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.rawResponseType()).isEqualTo(List.class);
        assertThat(meta.responseType()).isInstanceOf(ParameterizedType.class);

        ParameterizedType pt = (ParameterizedType) meta.responseType();
        assertThat(pt.getRawType()).isEqualTo(List.class);
        assertThat(pt.getActualTypeArguments()[0]).isEqualTo(Item.class);
    }

    @Test
    @DisplayName("Scanning a non-interface throws IllegalArgumentException")
    void rejectsNonInterface() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(NotAnInterface.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REST client must be an interface");
    }

    @Test
    @DisplayName("Method with non-Future return type throws IllegalArgumentException")
    void rejectsNonFutureReturn() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(NonFutureReturnClient.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must return Future<T>");
    }

    @Test
    @DisplayName("@DefaultValue is captured in parameter metadata")
    void defaultValueIsCaptured() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(DefaultValueClient.class);

        assertThat(metas).hasSize(1);
        ClientParamMeta param = metas.values().iterator().next().params().get(0);
        assertThat(param.source()).isEqualTo(ClientParamMeta.ParamSource.QUERY);
        assertThat(param.name()).isEqualTo("page");
        assertThat(param.defaultValue()).isEqualTo("0");
    }

    @Test
    @DisplayName("Double slashes in path are collapsed to single slash")
    void pathNormalizationCollapsesDoubleSlashes() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(DoubleSlashPathClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.pathTemplate()).doesNotContain("//");
        assertThat(meta.pathTemplate()).startsWith("/");
    }

    @Test
    @DisplayName("Class-level and method-level @Path values are combined")
    void classAndMethodPathAreCombined() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(ClassAndMethodPathClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.pathTemplate()).isEqualTo("/base/resource");
    }

    @Test
    @DisplayName("Default media type is application/json when no @Consumes/@Produces are present")
    void defaultMediaTypeIsJson() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(GetListClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.consumesMediaType()).isEqualTo("application/json");
        assertThat(meta.producesMediaType()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("Method with @Retry(maxRetries=5) produces RetryConfig with maxRetries=5")
    void methodLevelRetryProducesRetryConfig() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(MethodLevelRetryClient.class);
        Map<String, ClientMethodMeta> byName = indexByMethodName(metas);

        ClientMethodMeta withRetry = byName.get("withRetry");
        assertThat(withRetry.resilience()).isNotNull();
        assertThat(withRetry.resilience().retry()).isNotNull();
        assertThat(withRetry.resilience().retry().maxRetries()).isEqualTo(5);
        assertThat(withRetry.resilience().retry().backoffClass()).isEqualTo(NoDelayBackoff.class);
    }

    @Test
    @DisplayName("Method without @Retry has null retryConfig")
    void methodWithoutRetryHasNullRetryConfig() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(MethodLevelRetryClient.class);
        Map<String, ClientMethodMeta> byName = indexByMethodName(metas);

        ClientMethodMeta noRetry = byName.get("noRetry");
        // No resilience config at all — resilience is null
        assertThat(noRetry.resilience()).isNull();
    }

    @Test
    @DisplayName("Interface-level @Retry is applied to method without its own annotation")
    void interfaceLevelRetryIsInheritedByMethod() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(InterfaceLevelRetryClient.class);
        Map<String, ClientMethodMeta> byName = indexByMethodName(metas);

        ClientMethodMeta inherited = byName.get("inherited");
        assertThat(inherited.resilience()).isNotNull();
        assertThat(inherited.resilience().retry()).isNotNull();
        assertThat(inherited.resilience().retry().maxRetries()).isEqualTo(3);
    }

    @Test
    @DisplayName("Method-level @Retry overrides interface-level @Retry")
    void methodLevelRetryOverridesInterfaceLevel() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(InterfaceLevelRetryClient.class);
        Map<String, ClientMethodMeta> byName = indexByMethodName(metas);

        ClientMethodMeta override = byName.get("overrideRetry");
        assertThat(override.resilience()).isNotNull();
        assertThat(override.resilience().retry()).isNotNull();
        // Method-level maxRetries=1 must override interface-level maxRetries=3
        assertThat(override.resilience().retry().maxRetries()).isEqualTo(1);
    }

    // --- @Url tests ---

    @Test
    @DisplayName("@Url URI parameter is detected as ParamSource.URL with null name")
    void urlParamIsDetectedAsUrlSource() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(UrlGetClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();
        assertThat(meta.params()).hasSize(1);

        ClientParamMeta urlParam = meta.params().get(0);
        assertThat(urlParam.source()).isEqualTo(ClientParamMeta.ParamSource.URL);
        assertThat(urlParam.type()).isEqualTo(URI.class);
        assertThat(urlParam.name()).isNull();
    }

    @Test
    @DisplayName("@Url with @QueryParam on different parameters is allowed")
    void urlWithQueryParamOnDifferentParamsIsAllowed() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(UrlWithQueryClient.class);

        assertThat(metas).hasSize(1);
        List<ClientParamMeta> params = metas.values().iterator().next().params();
        assertThat(params).hasSize(2);
        assertThat(params)
                .extracting(ClientParamMeta::source)
                .containsExactly(ClientParamMeta.ParamSource.URL, ClientParamMeta.ParamSource.QUERY);
    }

    @Test
    @DisplayName("@Url with @HeaderParam on different parameter is allowed")
    void urlWithHeaderParamIsAllowed() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(UrlWithHeaderClient.class);

        assertThat(metas).hasSize(1);
        List<ClientParamMeta> params = metas.values().iterator().next().params();
        assertThat(params).hasSize(2);
        assertThat(params)
                .extracting(ClientParamMeta::source)
                .containsExactly(ClientParamMeta.ParamSource.URL, ClientParamMeta.ParamSource.HEADER);
    }

    @Test
    @DisplayName("@Url with @CookieParam on different parameter is allowed")
    void urlWithCookieParamIsAllowed() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(UrlWithCookieClient.class);

        assertThat(metas).hasSize(1);
        List<ClientParamMeta> params = metas.values().iterator().next().params();
        assertThat(params).hasSize(2);
        assertThat(params)
                .extracting(ClientParamMeta::source)
                .containsExactly(ClientParamMeta.ParamSource.URL, ClientParamMeta.ParamSource.COOKIE);
    }

    @Test
    @DisplayName("@Url with unannotated body parameter on different parameter is allowed")
    void urlWithBodyParamIsAllowed() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(UrlWithBodyClient.class);

        assertThat(metas).hasSize(1);
        List<ClientParamMeta> params = metas.values().iterator().next().params();
        assertThat(params).hasSize(2);
        assertThat(params)
                .extracting(ClientParamMeta::source)
                .containsExactly(ClientParamMeta.ParamSource.URL, ClientParamMeta.ParamSource.BODY);
    }

    @Test
    @DisplayName("@Url with @BeanParam containing only @QueryParam fields is allowed")
    void urlWithBeanQueryParamIsAllowed() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(UrlWithBeanQueryClient.class);

        assertThat(metas).hasSize(1);
        List<ClientParamMeta> params = metas.values().iterator().next().params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0).source()).isEqualTo(ClientParamMeta.ParamSource.URL);
        assertThat(params.get(1).source()).isEqualTo(ClientParamMeta.ParamSource.BEAN_PARAM);
        assertThat(params.get(1).beanFields())
                .extracting(ClientParamMeta::source)
                .containsOnly(ClientParamMeta.ParamSource.QUERY);
    }

    @Test
    @DisplayName("hasUrlParam() returns true for a method with @Url parameter")
    void hasUrlParamReturnsTrueForUrlMethod() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(UrlGetClient.class);

        assertThat(metas).hasSize(1);
        assertThat(metas.values().iterator().next().hasUrlParam()).isTrue();
    }

    @Test
    @DisplayName("hasUrlParam() returns false for a method without @Url parameter")
    void hasUrlParamReturnsFalseForNormalMethod() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(GetListClient.class);

        assertThat(metas).hasSize(1);
        assertThat(metas.values().iterator().next().hasUrlParam()).isFalse();
    }

    @Test
    @DisplayName("@Url on a String parameter is rejected at scan time")
    void urlWithWrongTypeIsRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWrongTypeClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Two @Url parameters on the same method are rejected at scan time")
    void multipleUrlParamsRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(DoubleUrlClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@Url and @QueryParam on the same parameter are rejected at scan time")
    void urlWithQueryOnSameParamRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithQueryOnSameParamClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@Url and @PathParam on the same parameter are rejected at scan time")
    void urlWithPathOnSameParamRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithPathOnSameParamClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@Url and @HeaderParam on the same parameter are rejected at scan time")
    void urlWithHeaderOnSameParamRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithHeaderOnSameParamClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@Url and @CookieParam on the same parameter are rejected at scan time")
    void urlWithCookieOnSameParamRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithCookieOnSameParamClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@Url and @BeanParam on the same parameter are rejected at scan time")
    void urlWithBeanOnSameParamRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithBeanOnSameParamClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@Url and @FormParam on the same parameter are rejected at scan time")
    void urlWithFormParamOnSameParamRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithFormParamOnSameClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@Url and @MatrixParam on the same parameter are rejected at scan time")
    void urlWithMatrixParamOnSameParamRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithMatrixParamOnSameClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@DefaultValue on an @Url parameter is rejected at scan time")
    void urlWithDefaultValueRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithDefaultValueClient.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@DefaultValue is not allowed on @Url");
    }

    @Test
    @DisplayName("Method-level @Path combined with @Url is rejected at scan time")
    void urlWithMethodPathRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithMethodPathClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Interface-level @Path combined with @Url is rejected at scan time")
    void urlWithInterfacePathRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithInterfacePathClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@PathParam on another parameter alongside @Url is rejected at scan time")
    void urlWithPathParamOnOtherParamRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithPathParamOnOtherClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@BeanParam containing a @PathParam field alongside @Url is rejected at scan time")
    void urlWithBeanContainingPathParamRejected() {
        assertThatThrownBy(() -> ClientInterfaceScanner.scan(UrlWithBeanPathParamClient.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Phase 4.1 metadata-composition tests (RED) ---

    @Test
    @DisplayName("ClientParamMeta composes a ParameterMetadata view (rawType, name, source)")
    void clientParamMetaComposesParameterMetadata() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(QueryUuidClient.class);

        assertThat(metas).hasSize(1);
        ClientParamMeta param = metas.values().iterator().next().params().get(0);

        // Composed ParameterMetadata view — the green step adds parameterMetadata() to ClientParamMeta.
        ParameterMetadata pm = param.parameterMetadata();
        assertThat(pm.type()).isEqualTo(java.util.UUID.class);
        assertThat(pm.name()).isEqualTo("id");

        // Delegating accessors on ClientParamMeta still expose the composed values.
        assertThat(param.type()).isEqualTo(java.util.UUID.class);
        assertThat(param.name()).isEqualTo("id");
        assertThat(param.source()).isEqualTo(ClientParamMeta.ParamSource.QUERY);
    }

    @Test
    @DisplayName("ClientParamMeta carries componentType for a collection parameter")
    void clientParamMetaCarriesComponentType() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(QueryListUuidClient.class);

        assertThat(metas).hasSize(1);
        ClientParamMeta param = metas.values().iterator().next().params().get(0);

        assertThat(param.type()).isEqualTo(List.class);
        // componentType resolved from the parameter's generic type List<UUID> → UUID.
        assertThat(param.componentType()).isEqualTo(java.util.UUID.class);
    }

    @Test
    @DisplayName("ClientParamMeta.componentType is null for a non-collection parameter")
    void clientParamMetaComponentTypeNullForScalar() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(QueryUuidClient.class);

        assertThat(metas).hasSize(1);
        ClientParamMeta param = metas.values().iterator().next().params().get(0);

        assertThat(param.componentType()).isNull();
    }

    @Test
    @DisplayName("ClientParamMeta.annotationsLazy() reflectively returns the parameter's annotations")
    void clientParamMetaAnnotationsLazyReflective() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(AnnotatedParamClient.class);

        assertThat(metas).hasSize(1);
        ClientParamMeta param = metas.values().iterator().next().params().get(0);

        java.lang.annotation.Annotation[] annotations =
                param.parameterMetadata().annotationsLazy().get();
        List<Class<?>> annotationTypes = java.util.Arrays.stream(annotations)
                .<Class<?>>map(a -> a.annotationType())
                .toList();
        assertThat(annotationTypes).contains(QueryParam.class, DefaultValue.class);
    }

    @Test
    @DisplayName("ClientMethodMeta composes a MethodMetadata view exposing the method name")
    void clientMethodMetaComposesMethodMetadata() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(QueryUuidClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();

        // Composed MethodMetadata view — the green step adds methodMetadata() to ClientMethodMeta.
        MethodMetadata mm = meta.methodMetadata();
        assertThat(mm.name()).isEqualTo("doGet");
        assertThat(mm.declaringType()).isEqualTo(QueryUuidClient.class);
    }

    @Test
    @DisplayName("ClientMethodMeta exposes a domain responseGenericType for Future<List<Item>>")
    void clientMethodMetaCarriesResponseGenericType() {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(ResponseGenericClient.class);

        assertThat(metas).hasSize(1);
        ClientMethodMeta meta = metas.values().iterator().next();

        // responseGenericType() is the domain Type field retained from the unwrapped Future<T>.
        java.lang.reflect.Type responseGeneric = meta.responseGenericType();
        assertThat(responseGeneric).isInstanceOf(ParameterizedType.class);

        ParameterizedType pt = (ParameterizedType) responseGeneric;
        assertThat(pt.getRawType()).isEqualTo(List.class);
        assertThat(pt.getActualTypeArguments()[0]).isEqualTo(Item.class);
    }

    @Test
    @DisplayName("ClientMethodMeta no longer exposes a live java.lang.reflect.Method")
    void clientMethodMetaDropsLiveMethod() {
        // Structural proof: the record/class must not expose a method() accessor returning Method.
        // RED today — ClientMethodMeta.method() returns java.lang.reflect.Method.
        boolean exposesLiveMethod = java.util.Arrays.stream(ClientMethodMeta.class.getMethods())
                .anyMatch(m -> m.getName().equals("method")
                        && m.getReturnType() == java.lang.reflect.Method.class
                        && m.getParameterCount() == 0);
        assertThat(exposesLiveMethod)
                .as("ClientMethodMeta must not expose a live java.lang.reflect.Method accessor")
                .isFalse();
    }

    // --- Helpers ---

    /**
     * Builds a name-to-metadata map for easier lookup by method name in tests.
     *
     * @param metas the scanned metadata map
     * @return a map from method name to metadata
     */
    private static Map<String, ClientMethodMeta> indexByMethodName(Map<Method, ClientMethodMeta> metas) {
        return metas.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getKey().getName(), Map.Entry::getValue));
    }
}
