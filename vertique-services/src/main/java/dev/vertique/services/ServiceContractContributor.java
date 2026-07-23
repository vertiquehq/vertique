// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * SPI for modules to contribute service contract entries to the registry at build time.
 *
 * <p>Implement this interface and contribute it via Dagger {@code @IntoSet} multibinding.
 * Contributed entries are merged into the {@link ServiceContractRegistry} alongside
 * default {@link ServiceContract}-annotated entries. Global address collision validation runs
 * across all entries.
 *
 * <p>Use the {@link ServiceContractEntries} builder to construct entries — do not
 * instantiate {@link ServiceContractRegistry.ContractEntry} or
 * {@link dev.vertique.services.dispatch.ServiceMethodMeta} directly.
 *
 * <pre>{@code
 * @Provides @IntoSet
 * static ServiceContractContributor myContributor(Set<MyHandler> handlers) {
 *     return config -> handlers.stream()
 *         .map(h -> ServiceContractEntries.deployable()
 *             .contract(h.getClass())
 *             .serviceInstance(h)
 *             .namespace("custom").name(h.name())
 *             .operation("execute")
 *                 .method(resolveMethod(h))
 *                 .payloadType(h.payloadType())
 *                 .returnType(Void.class)
 *                 .param("payload", ParamSource.PAYLOAD, h.payloadType())
 *                 .done()
 *             .deploymentOptions(config, "services", "custom", h.name())
 *             .build())
 *         .toList();
 * }
 * }</pre>
 */
public interface ServiceContractContributor {

    /**
     * Returns contract entries to merge into the registry.
     *
     * <p>Called once during {@link ServiceContractRegistry#build(java.util.Set, java.util.Set,
     * JsonObject)}. Implementations must not return {@code null}; return an empty list if there
     * are no entries to contribute.
     *
     * @param config the application configuration for deployment option resolution
     * @return contract entries to register; empty list if none
     */
    List<ServiceContractRegistry.ContractEntry<?>> contribute(JsonObject config);
}
