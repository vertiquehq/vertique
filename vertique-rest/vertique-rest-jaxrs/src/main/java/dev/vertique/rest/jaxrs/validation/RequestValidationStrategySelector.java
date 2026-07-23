// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import dev.vertique.rest.core.RestConfigurationException;
import java.util.Set;

/**
 * Resolves the configured request-validation strategy id against the set of registered
 * {@link RequestValidationStrategy} instances, matching on each strategy's {@link
 * RequestValidationStrategy#id()}.
 *
 * <p>Selection fails fast: when no registered strategy carries the configured id, this throws a
 * {@link RestConfigurationException} naming the configured id and listing the available ids (sorted).
 * There is no silent fallback to {@code none} and no special-casing of any particular id — the
 * registered {@code id()} values are the single source of truth, so a misconfiguration (e.g. selecting
 * {@code "web-validation"} without {@code vertique-rest-validation} on the classpath) surfaces at
 * startup rather than degrading validation silently.
 */
public final class RequestValidationStrategySelector {

    private RequestValidationStrategySelector() {}

    /**
     * Returns the registered strategy whose {@link RequestValidationStrategy#id()} equals
     * {@code configuredId}.
     *
     * @param configuredId the strategy id selected by configuration (e.g. {@code "web-validation"})
     * @param available    the registered strategies (the {@code Set<RequestValidationStrategy>}
     *                     multibinding)
     * @return the matching strategy
     * @throws RestConfigurationException when no registered strategy carries {@code configuredId}; the
     *     message names the configured id and lists the available ids sorted
     */
    public static RequestValidationStrategy select(String configuredId, Set<RequestValidationStrategy> available) {
        return available.stream()
                .filter(strategy -> strategy.id().equals(configuredId))
                .findFirst()
                .orElseThrow(() -> new RestConfigurationException("No request-validation strategy registered with id '"
                        + configuredId + "'. Available strategy ids: "
                        + available.stream()
                                .map(RequestValidationStrategy::id)
                                .sorted()
                                .toList()));
    }
}
