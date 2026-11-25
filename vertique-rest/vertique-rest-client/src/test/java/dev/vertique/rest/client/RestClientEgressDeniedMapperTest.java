// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.rest.client.exception.RestClientException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The default exception mapper translates a refused redirect hop into a {@link RestClientException}.
 */
class RestClientEgressDeniedMapperTest {

    @Test
    @DisplayName("An egress refusal maps to a RestClientException with a constant message")
    void egressDeniedMapsToARestClientExceptionWithAConstantMessage() {
        // Given the default mapper with only its pre-registered translations
        DefaultRestClientExceptionMapper mapper = new DefaultRestClientExceptionMapper();

        Set<String> messages = new LinkedHashSet<>();
        for (HttpEgressDeniedException.Reason reason : HttpEgressDeniedException.Reason.values()) {
            HttpEgressDeniedException refusal = new HttpEgressDeniedException(reason);

            // When the refusal is translated
            Throwable mapped = mapper.translate(refusal);

            // Then it is a plain RestClientException (not a connection or timeout subtype) that
            // keeps the refusal as its cause and carries a message with no target details
            assertThat(mapped).as("reason %s", reason).isExactlyInstanceOf(RestClientException.class);
            assertThat(mapped.getCause()).isSameAs(refusal);
            assertThat(mapped.getMessage()).isNotBlank();
            assertThat(mapped.getMessage()).doesNotContain("://", "@", reason.name());
            messages.add(mapped.getMessage());
        }

        // And the message is the same constant whatever the reason
        assertThat(messages).hasSize(1);
    }
}
