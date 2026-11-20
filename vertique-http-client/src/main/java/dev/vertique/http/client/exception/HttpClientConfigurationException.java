// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown when the HTTP client builder holds an invalid setting. The message is constant for a given
 * rule and never contains the rejected value.
 */
public class HttpClientConfigurationException extends ConfigurationException {

    /**
     * Creates a configuration exception.
     *
     * @param message the constant description of the rejected setting
     */
    public HttpClientConfigurationException(String message) {
        super(message);
    }
}
