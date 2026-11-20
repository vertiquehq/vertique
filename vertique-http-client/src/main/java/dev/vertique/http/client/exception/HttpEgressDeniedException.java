// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import dev.vertique.core.exception.ValidationException;
import java.util.Objects;

/**
 * Thrown when the egress policy or the redirect rules refuse a request target. The message is constant;
 * {@link #reason()} names the rule that refused.
 */
public final class HttpEgressDeniedException extends ValidationException {

    private static final String MESSAGE = "The request target was refused by the egress rules";

    /** The rule that refused a target. */
    public enum Reason {
        /** The target has no host. */
        NO_HOST,
        /** The target carries user information. */
        USER_INFO,
        /** The target's scheme is not allowed. */
        SCHEME,
        /** The target's host is not allowed. */
        HOST,
        /** The address the connection would use is not allowed. */
        CONNECT_ADDRESS,
        /** The virtual host (the {@code Host} header or the TLS server name) is not allowed. */
        VIRTUAL_HOST,
        /** The proxy the request would use is not allowed. */
        PROXY,
        /** Credentials would travel over plain HTTP and the client does not allow that. */
        PLAIN_HTTP_CREDENTIALS
    }

    private final Reason reason;

    /**
     * Creates an egress refusal.
     *
     * @param reason the rule that refused the target
     * @throws NullPointerException if {@code reason} is null
     */
    public HttpEgressDeniedException(Reason reason) {
        super(MESSAGE);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the rule that refused the target.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
