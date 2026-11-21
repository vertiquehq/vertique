// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.inboxoutbox.exception.InboxOutboxConfigurationException;
import jakarta.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuration for the Kafka outbox destination.
 *
 * <p>Deserialized from the {@code inboxOutbox.kafka} section of the application config.
 * {@link #outboundContextNamespaces()} names the stored durable-context namespaces that leave the
 * process as {@code vertique-<namespace>} headers when a {@code KAFKA} outbox row is sent. A config
 * that does not set the key forwards every namespace the row holds except the signed
 * {@code identity-snapshot}; an explicit list replaces that default and forwards exactly the listed
 * namespaces, so an empty list forwards none and a list naming {@code identity-snapshot} forwards the
 * identity. The handler rejects a listed name no registered durable-context encoder owns when it is
 * built; this record rejects a blank or repeated name when the configuration is parsed.
 *
 * <p>Example YAML:
 * <pre>{@code
 * inboxOutbox:
 *   kafka:
 *     outboundContextNamespaces: [correlation, localization, identity-snapshot]
 * }</pre>
 *
 * @param outboundContextNamespaces the stored-context namespaces a {@code KAFKA} outbox row forwards as
 *     headers, or {@code null} for the default (every namespace except {@code identity-snapshot}); an
 *     empty list forwards none
 */
public record KafkaOutboxConfig(@Nullable List<String> outboundContextNamespaces) {

    /** The configuration key under {@code inboxOutbox.kafka} that holds the outbound namespace list. */
    public static final String OUTBOUND_CONTEXT_NAMESPACES_KEY = "outboundContextNamespaces";

    /** The configuration path of the outbound namespace list, as named in error messages. */
    static final String OUTBOUND_CONTEXT_NAMESPACES_PATH = "inboxOutbox.kafka." + OUTBOUND_CONTEXT_NAMESPACES_KEY;

    /**
     * Validates the list and keeps an immutable copy of it.
     *
     * @throws InboxOutboxConfigurationException if a listed name is {@code null}, blank or repeated;
     *     the message names the configuration path and the position, never the offending text, which
     *     an operator may have mistyped from something else
     */
    public KafkaOutboxConfig {
        if (outboundContextNamespaces != null) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < outboundContextNamespaces.size(); i++) {
                String namespace = outboundContextNamespaces.get(i);
                if (namespace == null || namespace.isBlank()) {
                    throw new InboxOutboxConfigurationException(
                            OUTBOUND_CONTEXT_NAMESPACES_PATH + "[" + i + "] must be a non-blank namespace name");
                }
                if (!seen.add(namespace)) {
                    throw new InboxOutboxConfigurationException(
                            OUTBOUND_CONTEXT_NAMESPACES_PATH + "[" + i + "] repeats an earlier entry");
                }
            }
            outboundContextNamespaces = List.copyOf(outboundContextNamespaces);
        }
    }

    /**
     * Creates the config from its JSON form.
     *
     * @param outboundContextNamespaces the configured list, or {@code null} when the key is absent
     * @return the validated config
     */
    @JsonCreator
    static KafkaOutboxConfig fromJson(
            @JsonProperty(OUTBOUND_CONTEXT_NAMESPACES_KEY) @Nullable List<String> outboundContextNamespaces) {
        return new KafkaOutboxConfig(outboundContextNamespaces);
    }

    /**
     * Returns the default configuration.
     *
     * @return a config that forwards every namespace except {@code identity-snapshot}
     */
    public static KafkaOutboxConfig defaults() {
        return new KafkaOutboxConfig(null);
    }
}
