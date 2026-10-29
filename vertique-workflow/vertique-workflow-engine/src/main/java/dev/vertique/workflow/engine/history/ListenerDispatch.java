// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * INTERNAL: calls observer-style listeners in isolation, so that nothing a listener throws reaches the action it
 * observes or stops the listeners after it.
 *
 * <p>An {@link Exception} is handed to the failure callback. An {@link Error} is a programming or VM failure and
 * propagates.
 */
public final class ListenerDispatch {

    private ListenerDispatch() {
        // static helpers only
    }

    /**
     * Calls every listener in order, handing what a listener throws to {@code onFailure} and going on with the next.
     *
     * @param listeners the listeners, in call order
     * @param call      the call made on each listener
     * @param onFailure receives the listener and what it threw
     * @param <L>       the listener type
     */
    public static <L> void forEach(List<L> listeners, Consumer<L> call, BiConsumer<L, Throwable> onFailure) {
        for (L listener : listeners) {
            try {
                call.accept(listener);
            } catch (Exception thrown) {
                onFailure.accept(listener, thrown);
            }
        }
    }
}
