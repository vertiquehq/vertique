// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

/**
 * The facts the two twin search resources share: {@link GeneratedSearchResource}, which the
 * generated descriptor path describes, and {@link ReflectedSearchResource}, which the reflective
 * scanner describes. Both declare one {@code GET} operation with the same bindings, in the same
 * order; only their operation ids and composite classes differ.
 *
 * <p>The operation's inputs, in declaration order: path {@code id} with a {@code @Pattern}; a
 * {@code @BeanParam} bean (without {@code @Valid}) holding query {@code q} with {@code @Size} and a
 * description, header {@code X-Tenant}, and query {@code limit} defaulting to {@code 10}; query
 * {@code sku} with {@code @NotNull}; primitive query {@code verbose} without a default; a {@code
 * @RequestParams} record holding query {@code sort}; and primitive query {@code page} defaulting to
 * {@code 1}. The bean sits between {@code id} and {@code sku} and the record before {@code page}, so
 * a document that kept the composite fields in plain declaration order differs from one that lists
 * the method parameters first.
 */
public final class TwinInputs {

    /** The resource path both twins declare. */
    public static final String RESOURCE_PATH = "/search";

    /** The method path both twins declare. */
    public static final String METHOD_PATH = "/{id}";

    /** The route template relative to the mount, as a document publishes it. */
    public static final String ROUTE = RESOURCE_PATH + METHOD_PATH;

    /** The regular expression of the {@code id} path parameter's {@code @Pattern}. */
    public static final String ID_PATTERN = "^[0-9]+$";

    /** The description of the bean's {@code q} field. */
    public static final String Q_DESCRIPTION = "Search text";

    /** The {@code @Size(max)} of the bean's {@code q} field. */
    public static final int Q_MAX = 20;

    /** The raw {@code @DefaultValue} of the bean's {@code limit} field. */
    public static final String LIMIT_DEFAULT = "10";

    /** The raw {@code @DefaultValue} of the {@code page} method parameter. */
    public static final String PAGE_DEFAULT = "1";

    /** The header name of the bean's tenant field. */
    public static final String TENANT_HEADER = "X-Tenant";

    private TwinInputs() {}
}
