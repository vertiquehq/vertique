// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.SharedDeployment;
import dev.vertique.rest.openapi.docs.fixture.startup.contract.TestValidationStrategies;
import io.vertx.core.json.JsonObject;

/**
 * Builders for the served-contract test configurations. Every builder returns a fresh {@link
 * JsonObject} whose server binds {@code 127.0.0.1} on port {@code 0}, whose {@code
 * jaxrs.validationStrategy} is {@value #WEB_VALIDATION} unless its name says otherwise, and whose
 * {@code jaxrs.openapiPath} is the global contract {@value ContractFiles#GLOBAL}. No builder sets an
 * {@code apidocs} entry unless its name says so. Modifiers change the given object in place and return
 * it.
 *
 * <p>A configuration names under {@code jaxrs.applications} and {@code apidocs.documents} only
 * applications its component registers: {@link #shared()} names {@code orders}, so it suits only a
 * component registering {@code orders}.
 */
public final class ContractConfigs {

    /** The id of the annotation-driven validation strategy. */
    public static final String WEB_VALIDATION = "web-validation";

    /** The id of the strategy that validates nothing. */
    public static final String NONE = "none";

    /** The id of the contract-validation strategy. */
    public static final String OPENAPI_CONTRACT = "openapi-contract";

    /** The id of the custom test strategy that reports resolving operations from the mount's contract. */
    public static final String CUSTOM_CONTRACT_TEST = TestValidationStrategies.CONTRACT_TEST_ID;

    /** The configured {@code apidocs.documents.partner.info.title}; it carries the marker. */
    public static final String CONFIGURED_INFO_TITLE = ContractFiles.MARKER + " configured title";

    /** The configured {@code apidocs.documents.partner.info.version}. */
    public static final String CONFIGURED_INFO_VERSION = "1";

    /** The configured {@code apidocs.documents.partner.serverUrl}; it carries the marker. */
    public static final String CONFIGURED_SERVER_URL = "https://" + ContractFiles.MARKER + ".example/api/partner";

    /** The configured permissive default {@code Cache-Control} of the protected-access configuration. */
    public static final String DEFAULT_CACHE_CONTROL = SharedDeployment.DEFAULT_CACHE_CONTROL;

    private ContractConfigs() {}

    /**
     * Returns the loopback configuration: {@value #WEB_VALIDATION}, {@code jaxrs.openapiPath}
     * {@value ContractFiles#GLOBAL}, and nothing else.
     *
     * @return a fresh configuration
     */
    public static JsonObject loopback() {
        return new JsonObject()
                .put("http", new JsonObject().put("host", "127.0.0.1").put("port", 0))
                .put(
                        "jaxrs",
                        new JsonObject()
                                .put("validationStrategy", WEB_VALIDATION)
                                .put("openapiPath", ContractFiles.GLOBAL));
    }

    /**
     * Returns the shared configuration: {@link #loopback()} plus {@code
     * jaxrs.applications.orders.openapiPath} {@value ContractFiles#ORDERS}. {@code partner}'s contract
     * comes from its declaring interface, {@code catalog} has none.
     *
     * @return a fresh configuration
     */
    public static JsonObject shared() {
        return withApplicationContract(loopback(), OrdersApi.NAME, ContractFiles.ORDERS);
    }

    /**
     * Returns {@link #shared()} plus {@code jaxrs.applications.partner.openapiPath}.
     *
     * @param location the configured location, such as {@value ContractFiles#PARTNER_OVERRIDE}
     * @return a fresh configuration
     */
    public static JsonObject sharedWithPartnerContract(String location) {
        return withApplicationContract(shared(), PartnerApi.NAME, location);
    }

    /**
     * Returns {@link #shared()} with the {@value #OPENAPI_CONTRACT} strategy selected.
     *
     * @return a fresh configuration
     */
    public static JsonObject sharedUnderOpenApiContract() {
        return withStrategy(shared(), OPENAPI_CONTRACT);
    }

    /**
     * Returns {@link #sharedUnderOpenApiContract()} plus {@code jaxrs.applications.partner.openapiPath}
     * {@value ContractFiles#PARTNER_STRATEGY}: {@code partner}'s contract without a {@code servers}
     * member, because the {@value #OPENAPI_CONTRACT} strategy accepts only absolute server URLs or none.
     *
     * @return a fresh configuration
     */
    public static JsonObject sharedUnderOpenApiContractWithPartnerStrategyContract() {
        return withApplicationContract(sharedUnderOpenApiContract(), PartnerApi.NAME, ContractFiles.PARTNER_STRATEGY);
    }

    /**
     * Returns {@link #shared()} with the {@value #CUSTOM_CONTRACT_TEST} strategy selected.
     *
     * @return a fresh configuration
     */
    public static JsonObject sharedUnderCustomContractTest() {
        return withStrategy(shared(), CUSTOM_CONTRACT_TEST);
    }

    /**
     * Returns {@link #loopback()} for a component registering {@code partner} alone; {@code partner}'s
     * contract comes from its declaring interface.
     *
     * @return a fresh configuration
     */
    public static JsonObject partnerOnly() {
        return loopback();
    }

    /**
     * Returns {@link #partnerOnly()} with the {@value #NONE} strategy selected, for a component whose
     * schema source is the deterministic counting source.
     *
     * @return a fresh configuration
     */
    public static JsonObject partnerOnlyWithoutValidation() {
        return withStrategy(partnerOnly(), NONE);
    }

    /**
     * Returns {@link #loopback()} plus {@code jaxrs.applications.alpha.openapiPath} and {@code
     * jaxrs.applications.beta.openapiPath}.
     *
     * @param alphaLocation the location configured for {@code alpha}
     * @param betaLocation  the location configured for {@code beta}
     * @return a fresh configuration
     */
    public static JsonObject alphaBeta(String alphaLocation, String betaLocation) {
        JsonObject config = withApplicationContract(loopback(), AlphaApi.NAME, alphaLocation);
        return withApplicationContract(config, BetaApi.NAME, betaLocation);
    }

    /**
     * Returns {@link #alphaBeta} with the {@value #OPENAPI_CONTRACT} strategy selected.
     *
     * @param alphaLocation the location configured for {@code alpha}
     * @param betaLocation  the location configured for {@code beta}
     * @return a fresh configuration
     */
    public static JsonObject alphaBetaUnderOpenApiContract(String alphaLocation, String betaLocation) {
        return withStrategy(alphaBeta(alphaLocation, betaLocation), OPENAPI_CONTRACT);
    }

    /**
     * Returns the protected-access configuration: {@link #shared()} plus {@code
     * jaxrs.defaultHeaders.cacheControl} {@value #DEFAULT_CACHE_CONTROL} and {@code
     * jwt.validation.issuer} {@value SharedDeployment#ISSUER}. It names {@code orders} and suits a
     * component registering {@code partner}, {@code management}, and {@code orders}.
     *
     * @return a fresh configuration
     */
    public static JsonObject protectedAccess() {
        JsonObject config = shared();
        config.getJsonObject("jaxrs")
                .put("defaultHeaders", new JsonObject().put("cacheControl", DEFAULT_CACHE_CONTROL));
        return withJwtIssuer(config);
    }

    /**
     * Returns the restricted configuration: {@link #loopback()} plus {@code jwt.validation.issuer}
     * {@value SharedDeployment#ISSUER}, for a component registering {@code partner} alone.
     *
     * @return a fresh configuration
     */
    public static JsonObject restricted() {
        return withJwtIssuer(loopback());
    }

    /**
     * Sets {@code jaxrs.validationStrategy}.
     *
     * @param config   the configuration to change
     * @param strategy the strategy id
     * @return {@code config}
     */
    public static JsonObject withStrategy(JsonObject config, String strategy) {
        config.getJsonObject("jaxrs").put("validationStrategy", strategy);
        return config;
    }

    /**
     * Sets {@code jaxrs.applications.<name>.openapiPath}, keeping every other application's entry.
     *
     * @param config   the configuration to change
     * @param name     the application's name
     * @param location the configured location
     * @return {@code config}
     */
    public static JsonObject withApplicationContract(JsonObject config, String name, String location) {
        JsonObject jaxrs = config.getJsonObject("jaxrs");
        JsonObject applications = jaxrs.getJsonObject("applications");
        if (applications == null) {
            applications = new JsonObject();
            jaxrs.put("applications", applications);
        }
        applications.put(name, new JsonObject().put("openapiPath", location));
        return config;
    }

    /**
     * Sets {@code apidocs.documents.<name>.enabled}.
     *
     * @param config  the configuration to change
     * @param name    the document's name
     * @param enabled the value
     * @return {@code config}
     */
    public static JsonObject withDocumentEnabled(JsonObject config, String name, boolean enabled) {
        return DocsConfigs.withDocumentEnabled(config, name, enabled);
    }

    /**
     * Sets {@code apidocs.documents.partner.info}: title {@value #CONFIGURED_INFO_TITLE}, version
     * {@value #CONFIGURED_INFO_VERSION}.
     *
     * @param config the configuration to change
     * @return {@code config}
     */
    public static JsonObject withPartnerInfo(JsonObject config) {
        return DocsConfigs.withDocumentInfo(config, PartnerApi.NAME, CONFIGURED_INFO_TITLE, CONFIGURED_INFO_VERSION);
    }

    /**
     * Sets {@code apidocs.documents.partner.serverUrl} to {@value #CONFIGURED_SERVER_URL}.
     *
     * @param config the configuration to change
     * @return {@code config}
     */
    public static JsonObject withPartnerServerUrl(JsonObject config) {
        DocsConfigs.document(config, PartnerApi.NAME).put("serverUrl", CONFIGURED_SERVER_URL);
        return config;
    }

    /**
     * Sets {@code apidocs.path}.
     *
     * @param config the configuration to change
     * @param path   the documentation prefix
     * @return {@code config}
     */
    public static JsonObject withApidocsPath(JsonObject config, String path) {
        return DocsConfigs.withApidocsPath(config, path);
    }

    /**
     * Sets {@code jwt.validation.issuer} to {@value SharedDeployment#ISSUER}.
     *
     * @param config the configuration to change
     * @return {@code config}
     */
    private static JsonObject withJwtIssuer(JsonObject config) {
        config.put("jwt", new JsonObject().put("validation", new JsonObject().put("issuer", SharedDeployment.ISSUER)));
        return config;
    }
}
