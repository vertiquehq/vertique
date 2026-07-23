// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

/**
 * Metadata for a path parameter binding on a WebSocket lifecycle method.
 *
 * @param name           the parameter name from {@code @PathParam}
 * @param parameterIndex the index in the method's parameter list
 * @param type           the declared parameter type
 */
record PathParamMeta(String name, int parameterIndex, Class<?> type) {}
