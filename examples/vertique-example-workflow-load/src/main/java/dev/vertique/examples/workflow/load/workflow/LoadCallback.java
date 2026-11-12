// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.workflow;

import dev.vertique.workflow.contract.SignalDedupKeyed;

/**
 * The signal the dispatch handler records when it has finished its work.
 *
 * <p>The dedup key depends only on the instance key, so every delivery attempt of the same dispatch uses the same
 * key and the engine records the signal once.
 *
 * @param key the key of the instance the signal belongs to
 */
public record LoadCallback(String key) implements SignalDedupKeyed {

    /** The name of the signal the workflow waits for. */
    public static final String SIGNAL_NAME = "load.callback";

    /**
     * Returns the dedup key of the callback of an instance.
     *
     * @param key the key of the instance
     * @return the same value for every delivery attempt of that instance's dispatch
     */
    public static String dedupKeyOf(String key) {
        return "load-callback:" + key;
    }

    /**
     * Returns the dedup key of this callback.
     *
     * @return {@link #dedupKeyOf(String)} of the key
     */
    @Override
    public String dedupKey() {
        return dedupKeyOf(key);
    }
}
