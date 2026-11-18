// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

/**
 * Carries a failure through a resilience pipeline so that the default retry classification refuses to
 * repeat it. The client unwraps it before the caller sees the failure, so it never leaves the client.
 */
final class NonRetryableFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NonRetryableFailure(Throwable original) {
        super(null, original, false, false);
    }

    /** Returns the failure a caller must see: the wrapped one, or {@code failure} itself. */
    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof NonRetryableFailure wrapper && wrapper.getCause() != null) {
            current = wrapper.getCause();
        }
        return current;
    }
}
