// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

/**
 * Per-call options for {@link VertiqueHttpClient#execute(CallOptions, java.util.function.Function)}.
 *
 * <p>Instances are immutable. {@link #defaults()} selects the client's own settings.
 */
public final class CallOptions {

    private static final CallOptions DEFAULTS = new CallOptions();

    private CallOptions() {}

    /**
     * Returns the options that apply the client's own settings.
     *
     * @return the default call options
     */
    public static CallOptions defaults() {
        return DEFAULTS;
    }
}
