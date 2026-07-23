// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.restclient;

/**
 * The TCP port the {@link MockServerVerticle} listens on.
 *
 * <p>A plain test-fixture value: {@code UserClientIT} constructs it directly (e.g.
 * {@code new MockConfig(0)} for an OS-allocated ephemeral port). It is no longer parsed from
 * application config — the example application is a pure REST client and stands up no mock server
 * of its own.
 *
 * @param port the TCP port the mock server should listen on; pass {@code 0} for an OS-allocated
 *     ephemeral port
 */
public record MockConfig(int port) {}
