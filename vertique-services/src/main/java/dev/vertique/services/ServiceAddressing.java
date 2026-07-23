// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

/**
 * Centralizes address and stable target id construction for service contracts.
 *
 * <p>All address and target id formatting passes through this class to avoid scattered
 * string concatenation and ensure consistency across registration, resolution, and
 * contributor paths.
 */
final class ServiceAddressing {

    /** Address prefix for all service operations. */
    static final String ADDRESS_PREFIX = "services";

    private ServiceAddressing() {}

    /**
     * Builds the runtime event bus address for a service operation.
     *
     * @param namespace the optional namespace segment (empty or null means absent)
     * @param name the service name
     * @param operationId the operation id
     * @return {@code services/{namespace}/{name}/{operationId}} or
     *     {@code services/{name}/{operationId}}
     */
    static String buildAddress(String namespace, String name, String operationId) {
        if (hasNamespace(namespace)) {
            return ADDRESS_PREFIX + "/" + namespace + "/" + name + "/" + operationId;
        }
        return ADDRESS_PREFIX + "/" + name + "/" + operationId;
    }

    /**
     * Builds the base address for a service contract (without the operation segment).
     *
     * @param namespace the optional namespace segment (empty or null means absent)
     * @param name the service name
     * @return {@code services/{namespace}/{name}} or {@code services/{name}}
     */
    static String buildBaseAddress(String namespace, String name) {
        if (hasNamespace(namespace)) {
            return ADDRESS_PREFIX + "/" + namespace + "/" + name;
        }
        return ADDRESS_PREFIX + "/" + name;
    }

    /**
     * Builds the stable target id for a service operation.
     *
     * <p>The stable target id uses dot-delimited segments and is suitable for durable
     * persistence. It does not depend on the runtime transport address format.
     *
     * @param namespace the optional namespace segment (empty or null means absent)
     * @param name the service name
     * @param operationId the operation id
     * @return {@code {namespace}.{name}.{operationId}} or {@code {name}.{operationId}}
     */
    static String buildStableTargetId(String namespace, String name, String operationId) {
        if (hasNamespace(namespace)) {
            return namespace + "." + name + "." + operationId;
        }
        return name + "." + operationId;
    }

    /**
     * Builds the stable contract id (without the operation segment).
     *
     * @param namespace the optional namespace segment (empty or null means absent)
     * @param name the service name
     * @return {@code {namespace}.{name}} or {@code {name}}
     */
    static String buildStableContractId(String namespace, String name) {
        if (hasNamespace(namespace)) {
            return namespace + "." + name;
        }
        return name;
    }

    private static boolean hasNamespace(String namespace) {
        return namespace != null && !namespace.isBlank();
    }
}
