// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.regex.Pattern;

/** A bounded summary of a policy decision made for an MCP request. */
public record McpAuthorizationSummary(
        boolean permitted,
        String reasonCode,
        @Nullable String policyId,
        @Nullable String policyVersion) {

    private static final Pattern REASON_CODE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final int MAX_POLICY_ID_CHARS = 256;
    private static final int MAX_POLICY_VERSION_CHARS = 64;

    /**
     * Validates the required, machine-readable decision reason and the bounded policy identity.
     *
     * <p>{@code reasonCode} matches {@code [a-z0-9][a-z0-9._-]{0,63}} — a lower-case machine token
     * of 1–64 characters. {@code policyId} and {@code policyVersion}, when present, are non-blank,
     * free of control characters, and at most {@value #MAX_POLICY_ID_CHARS} and
     * {@value #MAX_POLICY_VERSION_CHARS} characters respectively.
     *
     * @throws NullPointerException if {@code reasonCode} is null
     * @throws IllegalArgumentException if {@code reasonCode} does not match the machine-token
     *     grammar, or a present policy value is blank, too long, or carries a control character
     */
    public McpAuthorizationSummary {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!REASON_CODE_PATTERN.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode must match [a-z0-9][a-z0-9._-]{0,63}");
        }
        validateBounded(policyId, MAX_POLICY_ID_CHARS, "policyId");
        validateBounded(policyVersion, MAX_POLICY_VERSION_CHARS, "policyVersion");
    }

    private static void validateBounded(@Nullable String value, int maximumChars, String field) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when present");
        }
        if (value.length() > maximumChars) {
            throw new IllegalArgumentException(field + " must not exceed " + maximumChars + " characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
    }
}
