// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * A type annotated {@code @Hidden}. The output generator does not hide a type, so a member of this
 * type is still described; the output generator's hidden-member report holds the type-level entry
 * {@code (this type, null member, HIDDEN, hideableBySchemaHidden = false)}.
 */
@Hidden
public class AuditZx {

    /** A plain string member. */
    public String who;

    /** Creates an empty audit entry. */
    public AuditZx() {}

    /**
     * Creates an audit entry.
     *
     * @param who the actor
     */
    public AuditZx(String who) {
        this.who = who;
    }

    /**
     * Returns the actor.
     *
     * @return the actor
     */
    public String getWho() {
        return who;
    }
}
