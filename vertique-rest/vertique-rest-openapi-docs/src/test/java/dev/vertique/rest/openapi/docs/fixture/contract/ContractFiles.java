// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

/**
 * The contract locations of the served-contract fixtures, each a relative path as an {@code
 * openapiPath} names it. Unless its description says otherwise, a location is a test classpath
 * resource under {@code src/test/resources}. A location described as failing carries the marker
 * {@value #MARKER} in a description or value that no message may echo.
 */
public final class ContractFiles {

    /** The marker every failing fixture carries and no failure message may echo. */
    public static final String MARKER = "zq7";

    /**
     * The global contract ({@code jaxrs.openapiPath}): OpenAPI {@code 3.0.3}, mount-prefixed keys, no
     * {@code servers}, describing {@code listOrders}, {@code createOrder}, {@code listItems}, and
     * {@code listEntries} across the three shared applications.
     */
    public static final String GLOBAL = "contracts/global-openapi.json";

    /** {@code partner}'s own contract, named by its declaring interface: OpenAPI {@code 3.1.0}. */
    public static final String PARTNER = "contracts/partner-openapi.yaml";

    /** {@code partner}'s configured override: the same operations as {@link #PARTNER}, another title. */
    public static final String PARTNER_OVERRIDE = "contracts/partner-override-openapi.json";

    /** {@code orders}'s own contract, named only by configuration: OpenAPI {@code 3.0.3}. */
    public static final String ORDERS = "contracts/orders-openapi.json";

    /**
     * Valid: {@link #ORDERS} without a {@code servers} member, otherwise identical, for {@code orders}
     * under the contract-validation strategy.
     */
    public static final String ORDERS_STRATEGY = "contracts/orders-strategy-openapi.json";

    /** Failing: {@link #PARTNER} plus {@code deleteOrder}, an operation the mount does not route. */
    public static final String PARTNER_EXTRA_OPERATION = "contracts/partner-extra-operation.json";

    /** Failing: {@link #PARTNER} without {@code createOrder}. */
    public static final String PARTNER_MISSING_OPERATION = "contracts/partner-missing-operation.yaml";

    /** Failing: {@code createOrder}'s request schema is a {@code $ref} to another file. */
    public static final String PARTNER_EXTERNAL_REF = "contracts/partner-external-ref.yaml";

    /** Failing: invalid JSON whose first bad token is {@value #MARKER}. */
    public static final String PARTNER_MALFORMED_JSON = "contracts/partner-malformed.json";

    /** Failing: invalid YAML whose offending line carries {@value #MARKER}. */
    public static final String PARTNER_MALFORMED_YAML = "contracts/partner-malformed.yaml";

    /** Failing: no such file in the working directory and no such classpath resource. */
    public static final String ABSENT = "contracts/absent.json";

    /** Failing: a valid contract under an unsupported extension. */
    public static final String PARTNER_TXT = "contracts/partner-openapi.txt";

    /** Failing: {@link #PARTNER} plus a {@code webhooks} operation with the routed id {@code getOrderInternal}. */
    public static final String PARTNER_WEBHOOK_REUSE = "contracts/partner-webhook-reuse.yaml";

    /** Failing: {@link #PARTNER} also describing {@code listOrders}'s hidden query parameter {@code debug}. */
    public static final String PARTNER_HIDDEN_PARAM = "contracts/partner-hidden-param.yaml";

    /**
     * Failing: a YAML stream of two documents, the first exactly {@link #PARTNER} and the second a
     * mapping whose description carries {@value #MARKER}.
     */
    public static final String PARTNER_MULTI_DOCUMENT = "contracts/partner-multi-document.yaml";

    /**
     * Failing: a configured location with a line feed between {@code partner} and {@code
     * zz-openapi.txt}, and so an unsupported extension; no such file exists. Built from a {@code char}
     * so the source file holds no raw line feed.
     */
    public static final String PARTNER_LINE_FEED_TXT = "contracts/partner" + (char) 10 + "zz-openapi.txt";

    /** Valid: {@link #PARTNER} also describing the hidden operation {@code getOrderInternal}. */
    public static final String PARTNER_WITH_HIDDEN = "contracts/partner-with-hidden.yaml";

    /** Valid: {@link #PARTNER} without a {@code servers} member. */
    public static final String PARTNER_NO_SERVERS = "contracts/partner-no-servers.yaml";

    /**
     * Valid: {@link #PARTNER} without a {@code servers} member, otherwise identical, for {@code partner}
     * under the contract-validation strategy.
     */
    public static final String PARTNER_STRATEGY = "contracts/partner-strategy-openapi.yaml";

    /** Valid: {@link #PARTNER} with {@code servers[0].url} {@code https://partner.example.com/api/partner}. */
    public static final String PARTNER_ABSOLUTE_SERVER = "contracts/partner-absolute-server.yaml";

    /** Valid: {@link #PARTNER} with an empty {@code servers} array. */
    public static final String PARTNER_EMPTY_SERVERS = "contracts/partner-empty-servers.yaml";

    /**
     * The relative location that is a classpath resource with {@code info.title} {@code classpath}
     * and that a test may shadow with a working-directory file ({@link
     * ContractTexts#WORKING_DIRECTORY_SHADOWED}).
     */
    public static final String SHADOWED = "contracts/shadowed-openapi.json";

    /** A relative location that is not a classpath resource; a test writes it ({@link ContractTexts#LATE}). */
    public static final String LATE = "contracts/late-openapi.json";

    /** {@code partner}'s own contract in the restricted arrangement: no {@code security} member anywhere. */
    public static final String PARTNER_RESTRICTED = "contracts/partner-restricted-openapi.yaml";

    /** Failing when two applications name it: describes {@code listA} and {@code listB}. */
    public static final String SHARED = "contracts/shared-openapi.json";

    /** Failing when two applications name it under two spellings: describes {@code listA} and {@code listB}. */
    public static final String MGMT = "contracts/mgmt.yaml";

    /** {@link #MGMT} spelled with a leading {@code ./}: the same location after normalization. */
    public static final String MGMT_DOT_SLASH = "./contracts/mgmt.yaml";

    /** Valid for {@code beta} routing {@code GET /b}: describes only {@code listB}. */
    public static final String BETA = "contracts/beta-openapi.json";

    /** Failing for {@code alpha}: describes {@code GET /admin/users} with the routed id {@code listCatalog}. */
    public static final String ALPHA_BORROWS_BETA = "contracts/alpha-borrows-beta.json";

    /** Valid for {@code alpha} routing {@code GET /catalog}: describes only {@code listCatalog}. */
    public static final String ALPHA_CATALOG = "contracts/alpha-catalog-openapi.json";

    /** Valid for {@code beta} routing {@code GET /admin/users}: describes only {@code listUsers}. */
    public static final String BETA_ADMIN_USERS = "contracts/beta-admin-users-openapi.json";

    /**
     * A configured location containing a NUL character, which {@code java.nio.file.Path.of} rejects;
     * it also carries {@value #MARKER}. Built from a {@code char} so the source file holds no raw NUL.
     */
    public static final String NUL_LOCATION = "contracts/" + MARKER + "-alpha" + (char) 0 + ".json";

    private ContractFiles() {}
}
