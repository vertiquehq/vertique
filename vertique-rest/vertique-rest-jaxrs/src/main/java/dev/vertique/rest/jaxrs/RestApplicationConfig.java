// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import jakarta.annotation.Nullable;

/**
 * The parsed form of one {@code jaxrs.applications.<name>} entry of the strict keyed-object
 * {@code jaxrs.applications} configuration section. INTERNAL: package-private, not part of the
 * module's API, and bound nowhere in the Dagger graph.
 *
 * <p>{@link RestModule#parseJaxRsApplications} produces one instance per entry, in the section's
 * order, after its raw-key check and before its blank-value check.
 *
 * @param name the entry's key under {@code jaxrs.applications}, injected by the keyed-object parse
 * @param openapiPath the configured {@code jaxrs.applications.<name>.openapiPath} contract location,
 *     or {@code null} when the key is absent or set to JSON {@code null}
 */
record RestApplicationConfig(String name, @Nullable String openapiPath) {}
