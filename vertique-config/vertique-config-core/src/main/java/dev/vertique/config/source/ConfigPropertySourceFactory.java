// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import io.vertx.core.json.JsonObject;

/**
 * Factory SPI for creating {@link ConfigPropertySource} instances from configuration.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} and matched against
 * configured sources by {@link #type()}. Each configured source entry under
 * {@code config.propertySources[*]} carries a {@code type} field (e.g. {@code "vault"}); the
 * engine selects the factory whose {@code type()} equals that value.
 *
 * <h2>Registration</h2>
 * <p>Register an implementation in
 * {@code META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory} as a fully
 * qualified class name.
 *
 * <h2>Eager Providers</h2>
 * <p>Factories that connect to an external system during {@link #create(String, JsonObject)}
 * (eager providers) should perform all authentication and initial data fetching there. Any
 * exception thrown from {@code create} aborts startup immediately. This ensures that missing
 * credentials or unreachable backends are surfaced at boot time rather than at first use.
 *
 * <h2>Value Redaction</h2>
 * <p>Implementations MUST NOT include resolved secret values in any exception message, log
 * entry, or diagnostic output. Instance names and key names are safe to include.
 *
 * @see ConfigPropertySource
 * @see ConfigPropertySourceException
 */
public interface ConfigPropertySourceFactory {

    /**
     * Returns the type key used to match this factory against configured sources.
     *
     * <p>The value is matched against the {@code type} field of each entry in
     * {@code config.propertySources[*]}. Built-in type keys: {@code "vault"},
     * {@code "aws-secrets"}, {@code "azure-keyvault"}. Custom implementations
     * may return any non-blank key that does not collide with a registered factory.
     * Type keys are case-sensitive.
     *
     * @return the factory type key; never {@code null} or blank
     */
    String type();

    /**
     * Creates a new property source instance for the given named configuration entry.
     *
     * <p>Eager providers that connect to external systems should do so here. Any exception
     * thrown aborts startup. Implementations MUST include the instance {@code name} in
     * any error messages and MUST NOT include resolved values.
     *
     * @param name         the instance name from the configuration entry
     *                     (e.g. {@code "vault-primary"}); used for diagnostics only
     * @param sourceConfig the per-source configuration object from
     *                     {@code config.propertySources[*]}; never {@code null}
     * @return a ready-to-use {@link ConfigPropertySource}; never {@code null}
     * @throws ConfigPropertySourceException if the source cannot be initialised
     */
    ConfigPropertySource create(String name, JsonObject sourceConfig);
}
