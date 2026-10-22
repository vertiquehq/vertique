// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import java.util.Objects;

/**
 * Why a stored definition of a scope other than the default one is marked failing: newly deployed code conflicts with
 * the stored version, so new starts on it are refused while the application keeps running.
 *
 * <p>Only the {@link Rule} is stored. The {@code detail} holds keys and rule-level text and never document content,
 * state values or class names; a record read back from the store carries the rule's fixed description, see
 * {@link #of(Rule)}.
 *
 * @param rule the rule the version breaks
 * @param detail rule-level text that explains the failure; never document content or state
 */
public record DefinitionFailure(Rule rule, String detail) {

    /** The rules a stored version can break once new code is deployed. */
    public enum Rule {
        /** The version's state type no longer extends the state type of the default scope's definition of the id. */
        STATE_TYPE_NOT_EXTENDED,
        /** A type name that the version's name binding records no longer resolves to a class. */
        NAME_BINDING_UNRESOLVED
    }

    /** Validates the components. */
    public DefinitionFailure {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(detail, "detail");
    }

    /**
     * Returns the failure of the rule with the rule's fixed description as its detail.
     *
     * @param rule the rule the version breaks
     * @return the failure
     */
    public static DefinitionFailure of(Rule rule) {
        Objects.requireNonNull(rule, "rule");
        return new DefinitionFailure(
                rule,
                switch (rule) {
                    case STATE_TYPE_NOT_EXTENDED -> "the state type does not extend the default scope's state type";
                    case NAME_BINDING_UNRESOLVED -> "a type name in the recorded name binding no longer resolves";
                });
    }
}
