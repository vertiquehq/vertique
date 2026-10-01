// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * A case-insensitively bound request body whose member {@link #child} has a guarded schema. The
 * generator publishes the child schema twice (under {@code properties} and under the case-fold
 * {@code patternProperties} branch), so two guard copies exist, both listed in the manifest; the
 * root carries the non-ASCII refusal, which the manifest never lists.
 */
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
public class FoldCopyGuardZx {

    /** The guarded child, matched in any letter case. */
    public GuardedChildZx child;

    /** A plain published string member. */
    public String other;
}
