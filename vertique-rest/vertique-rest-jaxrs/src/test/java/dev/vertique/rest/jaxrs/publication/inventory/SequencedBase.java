// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.GroupSequence;

/**
 * Superclass of {@link SequencedResource} carrying a class-level {@code @GroupSequence}, which
 * redefines the {@code Default} group for its subclasses' validation.
 */
@GroupSequence({SequencedBase.class, Audit.class})
public class SequencedBase {}
