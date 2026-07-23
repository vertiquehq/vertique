// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import java.util.Objects;
import java.util.Optional;

/**
 * Verification source for credentials validated via mutual TLS (mTLS) using a configured
 * trust store.
 *
 * <p>The {@code trustStoreId} identifies the trust store configuration. The optional
 * {@code trustAnchorSubjectDn} narrows the trust to a specific CA distinguished name.
 *
 * @param trustStoreId           stable identifier of the trust store configuration; must not be
 *                               null or blank
 * @param trustAnchorSubjectDn   the subject DN of the trust anchor CA; empty when not restricted
 *                               to a specific anchor
 */
public record MtlsTrustStoreVerificationSource(String trustStoreId, Optional<String> trustAnchorSubjectDn)
        implements VerificationSource {

    /**
     * Compact constructor — validates that {@code trustStoreId} is non-null and non-blank,
     * and that {@code trustAnchorSubjectDn} is a non-null Optional.
     */
    public MtlsTrustStoreVerificationSource {
        Objects.requireNonNull(trustStoreId, "trustStoreId");
        if (trustStoreId.isBlank()) {
            throw new IllegalArgumentException("trustStoreId must not be blank");
        }
        Objects.requireNonNull(trustAnchorSubjectDn, "trustAnchorSubjectDn");
    }
}
