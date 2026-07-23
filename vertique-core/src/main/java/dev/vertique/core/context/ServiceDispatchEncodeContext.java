// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * Context passed to a {@link ServiceDispatchContextEncoder} at encode time.
 *
 * <p>Identifies the target dispatch boundary. Additional operation metadata may be added in future
 * versions without breaking existing encoder implementations.
 *
 * @param boundary the target dispatch boundary identifier (e.g., {@code "service-dispatch"})
 */
public record ServiceDispatchEncodeContext(String boundary) {}
