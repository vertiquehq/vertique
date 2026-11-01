// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.workflow.source.SignalNoMatch;

/**
 * The shared stages and the source-level default of what happens to a message that matches no
 * waiting instance.
 *
 * @param <T> the type of the decoded message body
 * @param <SELF> the type of the builder, returned by each stage so calls chain
 * @param <R> what {@link #build()} returns on the transport
 */
public interface SourceStages<T, SELF, R> extends SignalStages<T, SELF, R> {

    /**
     * Sets what a message that matches no instance does for every signal that does not set its own.
     * The default is {@link SignalNoMatch#IGNORE}.
     *
     * @param policy the source's default
     * @return this builder
     */
    SELF onNoMatch(SignalNoMatch policy);
}
