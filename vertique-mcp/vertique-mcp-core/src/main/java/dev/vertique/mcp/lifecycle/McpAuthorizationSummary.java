// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import jakarta.annotation.Nullable;
import java.util.Objects;

/** A bounded summary of a policy decision made for an MCP request. */
public record McpAuthorizationSummary(
        boolean permitted,
        String reasonCode,
        @Nullable String policyId,
        @Nullable String policyVersion) {

    /**
     * Validates the required, machine-readable decision reason.
     *
     * @throws NullPointerException if {@code reasonCode} is null
     * @throws IllegalArgumentException if {@code reasonCode} is blank
     */
    public McpAuthorizationSummary {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
    }
}
