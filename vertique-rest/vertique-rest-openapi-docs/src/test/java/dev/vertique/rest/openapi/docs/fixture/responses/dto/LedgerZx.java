// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * An output DTO whose member {@code audit} has the type {@link AuditZx}, which is annotated
 * {@code @Hidden}. The reachable hidden type is reported as a type-level entry naming {@code
 * AuditZx}.
 */
public class LedgerZx {

    /** A member whose type carries {@code @Hidden}. */
    public AuditZx audit;

    /** Creates an empty ledger. */
    public LedgerZx() {}

    /**
     * Creates a ledger.
     *
     * @param audit the audit entry
     */
    public LedgerZx(AuditZx audit) {
        this.audit = audit;
    }

    /**
     * Returns the audit entry.
     *
     * @return the audit entry
     */
    public AuditZx getAudit() {
        return audit;
    }
}
