// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.config;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Egress override read from {@code httpClient.{name}.egress}. {@code allowPlainHttp} is read only
 * together with {@code allowHosts}. {@link HttpClientConfig} validates that a present {@code
 * allowHosts} is not empty and holds only exact hosts, {@code *.suffix} entries or {@code *}.
 *
 * @param allowHosts the hosts the client may contact, or {@code null} to allow every host
 * @param allowPlainHttp whether plain HTTP to a listed host is allowed, or {@code null} for {@code false}
 */
public record HttpClientEgressConfig(
        @Nullable List<String> allowHosts, @Nullable Boolean allowPlainHttp) {

    /** Copies the host list so the record stays immutable. */
    public HttpClientEgressConfig {
        allowHosts = allowHosts == null ? null : List.copyOf(allowHosts);
    }
}
