// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Outcome of deep uploaded-file verification. A rejection is validation data that maps to a 400
 * response with a pointer to the part; a failed future is an infrastructure error that maps to 500.
 */
public sealed interface FileVerificationResult {

    /** Accepted verification outcome. */
    record Accepted() implements FileVerificationResult {
        private static final Accepted INSTANCE = new Accepted();
    }

    /**
     * Rejected verification outcome.
     *
     * @param detail the non-blank human-readable rejection detail
     * @param type   the non-blank rejection classification
     * @param args   optional, immutable rejection arguments
     */
    record Rejected(String detail, String type, @Nullable Map<String, Object> args) implements FileVerificationResult {

        /** Enforces a well-formed immutable rejection result. */
        public Rejected {
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("detail must be non-blank");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("type must be non-blank");
            }
            args = args == null ? null : Map.copyOf(args);
        }
    }

    /**
     * Returns the canonical accepted outcome. Result identity is not part of the contract.
     *
     * @return the accepted outcome
     */
    static FileVerificationResult accepted() {
        return Accepted.INSTANCE;
    }

    /**
     * Creates a rejection without arguments.
     *
     * @param detail the non-blank rejection detail
     * @param type   the non-blank rejection classification
     * @return the rejected outcome
     */
    static FileVerificationResult rejected(String detail, String type) {
        return new Rejected(detail, type, null);
    }

    /**
     * Creates a rejection with arguments.
     *
     * @param detail the non-blank rejection detail
     * @param type   the non-blank rejection classification
     * @param args   rejection arguments; defensively copied
     * @return the rejected outcome
     */
    static FileVerificationResult rejected(String detail, String type, Map<String, Object> args) {
        return new Rejected(detail, type, args);
    }
}
