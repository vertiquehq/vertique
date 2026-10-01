// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

/**
 * Records, over the assembly of one document, whether anything was withheld from it: an input left
 * out as hidden, or a reserved name removed from a published schema.
 *
 * <p>One tally is created per assembly and is not shared between assemblies. It is mutable and not
 * thread-safe; an assembly runs on one thread.
 */
final class DisclosureTally {

    private boolean hiddenInputs;
    private boolean reservedNamesRefused;

    /** Records that at least one input of a published operation was left out as hidden. */
    void hiddenInputOmitted() {
        hiddenInputs = true;
    }

    /** Records that at least one reserved name was removed from a published schema. */
    void reservedNameRemoved() {
        reservedNamesRefused = true;
    }

    /**
     * Returns whether any input of a published operation was left out as hidden.
     *
     * @return {@code true} once an input was omitted as hidden
     */
    boolean hiddenInputs() {
        return hiddenInputs;
    }

    /**
     * Returns whether any reserved name was removed from a published schema.
     *
     * @return {@code true} once a reserved name was removed
     */
    boolean reservedNamesRefused() {
        return reservedNamesRefused;
    }
}
