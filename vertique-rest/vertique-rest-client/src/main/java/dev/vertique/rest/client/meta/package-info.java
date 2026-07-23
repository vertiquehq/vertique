// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Internal metadata types for the REST client module.
 *
 * <p>Contains records and the scanner that extract JAX-RS annotation metadata from client
 * interfaces at startup time, producing {@link dev.vertique.rest.client.meta.ClientMethodMeta}
 * descriptors used by the proxy invocation handler at runtime.
 */
package dev.vertique.rest.client.meta;
