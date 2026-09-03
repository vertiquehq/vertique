// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience.config;

/** Partial named-tier timeout configuration.
 *
 * @param valueMs the positive timeout in milliseconds; required when the timeout concern is configured
 */
public record TimeoutPolicyConfig(Long valueMs) {}
