// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.runtime.bench;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hand-crafted generated descriptor companion for {@link BenchResource}.
 *
 * <p>Simulates the class that {@link dev.vertique.codegen.jaxrs.processor.emit.JaxRsDescriptorEmitter}
 * would produce at compile time. The FQN follows the algorithm from
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry#derivedFqn}:
 * same package as the resource class, simple name, plus the {@code _JaxRsDescriptor} suffix.
 *
 * <p>Used by {@link RegistrationBenchmark} to measure the generated-descriptor fast path.
 * For each of the 8 {@link BenchResource} methods, one {@link ResourceMethodMeta} entry is
 * returned without any reflective annotation walk — simulating the CG-010 generated path where
 * all metadata is known at compile time.
 */
public final class BenchResource_JaxRsDescriptor implements GeneratedJaxRsResourceDescriptor<BenchResource> {

    /** Pre-computed security policy shared by all methods (matches {@code @RolesAllowed("user")}). */
    private static final SecurityPolicy ROLES_USER = new SecurityPolicy.Constrained(List.of("user"), List.of(), false);

    /** Cached method references for the 8 endpoints, resolved once on class init. */
    private static final Method M_LIST;

    private static final Method M_GET;
    private static final Method M_CREATE;
    private static final Method M_UPDATE;
    private static final Method M_DELETE;
    private static final Method M_SEARCH;
    private static final Method M_PROCESS;
    private static final Method M_QUERY;

    static {
        try {
            M_LIST = BenchResource.class.getMethod("list", int.class);
            M_GET = BenchResource.class.getMethod("get", String.class);
            M_CREATE = BenchResource.class.getMethod("create", String.class);
            M_UPDATE = BenchResource.class.getMethod("update", String.class, String.class);
            M_DELETE = BenchResource.class.getMethod("delete", String.class);
            M_SEARCH = BenchResource.class.getMethod("search", String.class, int.class, int.class);
            M_PROCESS = BenchResource.class.getMethod("process", String.class, String.class, String.class);
            M_QUERY = BenchResource.class.getMethod("query", BenchQueryParams.class);
            for (Method m : new Method[] {M_LIST, M_GET, M_CREATE, M_UPDATE, M_DELETE, M_SEARCH, M_PROCESS, M_QUERY}) {
                m.setAccessible(true);
            }
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError("BenchResource method not found — fixture mismatch: " + e);
        }
    }

    /** No-arg constructor required for reflective instantiation by the registry. */
    public BenchResource_JaxRsDescriptor() {}

    @Override
    public Class<BenchResource> resourceType() {
        return BenchResource.class;
    }

    /**
     * Returns pre-computed {@link ResourceMethodMeta} entries for all 8 {@link BenchResource}
     * endpoints without performing any reflective annotation walk.
     *
     * @param resource   the resource instance
     * @param support    the runtime helper bag (unused — metadata is pre-computed)
     * @param violations the violation sink (nothing appended — fixture is conflict-free)
     * @return list of 8 method metadata entries
     */
    @Override
    public List<ResourceMethodMeta> describe(
            BenchResource resource, GeneratedJaxRsDescriptorSupport support, List<SecurityPolicyViolation> violations) {
        // --- pre-computed params ---
        ResourceMethodMeta.ParamMeta pageParam =
                new ResourceMethodMeta.ParamMeta("page", ResourceMethodMeta.ParamSource.QUERY, int.class);
        ResourceMethodMeta.ParamMeta idPathParam =
                new ResourceMethodMeta.ParamMeta("id", ResourceMethodMeta.ParamSource.PATH, String.class);
        ResourceMethodMeta.ParamMeta bodyParam =
                new ResourceMethodMeta.ParamMeta("body", ResourceMethodMeta.ParamSource.BODY, String.class);
        ResourceMethodMeta.ParamMeta qParam =
                new ResourceMethodMeta.ParamMeta("q", ResourceMethodMeta.ParamSource.QUERY, String.class);
        ResourceMethodMeta.ParamMeta limitParam =
                new ResourceMethodMeta.ParamMeta("limit", ResourceMethodMeta.ParamSource.QUERY, int.class);
        ResourceMethodMeta.ParamMeta offsetParam =
                new ResourceMethodMeta.ParamMeta("offset", ResourceMethodMeta.ParamSource.QUERY, int.class);
        ResourceMethodMeta.ParamMeta headerParam = new ResourceMethodMeta.ParamMeta(
                "X-Client-Version", ResourceMethodMeta.ParamSource.HEADER, String.class);
        ResourceMethodMeta.ParamMeta beanParam = new ResourceMethodMeta.ParamMeta(
                "params", ResourceMethodMeta.ParamSource.BEAN_PARAM, BenchQueryParams.class);

        ResourceMethodMeta.MediaTypes jsonJson =
                new ResourceMethodMeta.MediaTypes(List.of("application/json"), List.of("application/json"));
        ResourceMethodMeta.MediaTypes noConsumeJson =
                new ResourceMethodMeta.MediaTypes(List.of(), List.of("application/json"));

        return List.of(
                // list
                new ResourceMethodMeta(
                        resource,
                        M_LIST,
                        "bench-list",
                        "GET",
                        "/bench",
                        List.of(pageParam),
                        String.class,
                        false,
                        false,
                        ROLES_USER,
                        noConsumeJson,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                // get
                new ResourceMethodMeta(
                        resource,
                        M_GET,
                        "bench-get",
                        "GET",
                        "/bench/{id}",
                        List.of(idPathParam),
                        String.class,
                        false,
                        false,
                        ROLES_USER,
                        noConsumeJson,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                // create
                new ResourceMethodMeta(
                        resource,
                        M_CREATE,
                        "bench-create",
                        "POST",
                        "/bench",
                        List.of(bodyParam),
                        String.class,
                        false,
                        false,
                        ROLES_USER,
                        jsonJson,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                // update
                new ResourceMethodMeta(
                        resource,
                        M_UPDATE,
                        "bench-update",
                        "PUT",
                        "/bench/{id}",
                        List.of(idPathParam, bodyParam),
                        String.class,
                        false,
                        false,
                        ROLES_USER,
                        jsonJson,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                // delete
                new ResourceMethodMeta(
                        resource,
                        M_DELETE,
                        "bench-delete",
                        "DELETE",
                        "/bench/{id}",
                        List.of(idPathParam),
                        Void.class,
                        false,
                        true,
                        ROLES_USER,
                        noConsumeJson,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                // search
                new ResourceMethodMeta(
                        resource,
                        M_SEARCH,
                        "bench-search",
                        "GET",
                        "/bench/search",
                        List.of(qParam, limitParam, offsetParam),
                        String.class,
                        false,
                        false,
                        ROLES_USER,
                        noConsumeJson,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                // process
                new ResourceMethodMeta(
                        resource,
                        M_PROCESS,
                        "bench-process",
                        "POST",
                        "/bench/{id}/process",
                        List.of(idPathParam, headerParam, bodyParam),
                        String.class,
                        false,
                        false,
                        ROLES_USER,
                        jsonJson,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                // query
                new ResourceMethodMeta(
                        resource,
                        M_QUERY,
                        "bench-query",
                        "GET",
                        "/bench/query",
                        List.of(beanParam),
                        String.class,
                        false,
                        false,
                        ROLES_USER,
                        noConsumeJson,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
    }
}
