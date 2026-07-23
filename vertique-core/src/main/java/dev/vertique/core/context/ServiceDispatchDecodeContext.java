// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * Context passed to a {@link ServiceDispatchContextDecoder} at decode time.
 *
 * <p>Identifies the source dispatch boundary. Additional operation metadata may be added in future
 * versions without breaking existing decoder implementations.
 *
 * @param boundary the source dispatch boundary identifier (e.g., {@code "service-dispatch"})
 */
public record ServiceDispatchDecodeContext(String boundary) {}
