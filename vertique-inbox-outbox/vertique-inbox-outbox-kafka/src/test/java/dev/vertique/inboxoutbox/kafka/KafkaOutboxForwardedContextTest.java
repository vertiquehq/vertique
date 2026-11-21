// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.exception.InboxOutboxConfigurationException;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies which namespaces of an outbox row's stored durable context the Kafka destination hands to
 * the send: every namespace except the signed identity by default, exactly the listed ones with an
 * explicit list, and a startup failure for a list that names no registered namespace.
 */
@DisplayName("KafkaOutboxDestinationHandler forwarded context")
class KafkaOutboxForwardedContextTest {

    private static final String IDENTITY = "identity-snapshot";

    private final KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    record CorrelationValue() implements ContextValue {}

    record LocalizationValue() implements ContextValue {}

    record IdentityValue() implements ContextValue {}

    private static <T extends ContextValue> DurableContextMetadataEncoder<T> encoder(Class<T> type, String namespace) {
        return new DurableContextMetadataEncoder<>() {
            @Override
            public Class<T> type() {
                return type;
            }

            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public DurableMetadata encode(T value, DurableEncodeContext context) {
                return DurableMetadata.of(namespace, new JsonObject());
            }
        };
    }

    private static DurableContextMetadataRegistry registry(String... namespaces) {
        Map<String, DurableContextMetadataEncoder<?>> available = Map.of(
                "correlation",
                encoder(CorrelationValue.class, "correlation"),
                "localization",
                encoder(LocalizationValue.class, "localization"),
                IDENTITY,
                encoder(IdentityValue.class, IDENTITY));
        Set<DurableContextMetadataEncoder<?>> encoders = new HashSet<>();
        for (String namespace : namespaces) {
            encoders.add(available.get(namespace));
        }
        return new DurableContextMetadataRegistry(encoders, Set.of());
    }

    private static DurableMetadata storedContext() {
        return DurableMetadata.of("correlation", new JsonObject().put("traceId", "t-1"))
                .with(IDENTITY, new JsonObject().put("sub", "u-1"))
                .with("localization", new JsonObject().put("locale", "fi"));
    }

    private KafkaOutboxDestinationHandler handler(KafkaOutboxConfig config) {
        return new KafkaOutboxDestinationHandler(
                producerFactory, objectMapper, config, registry("correlation", "localization", IDENTITY));
    }

    private Set<String> sentNamespaces(KafkaOutboxDestinationHandler handler) {
        when(producerFactory.sendForOutbox(any(), any(), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(null));
        OutboxEnvelope envelope = new OutboxEnvelope(
                9L,
                "Order",
                "order-1",
                "order.placed",
                "orders-topic",
                new JsonObject().put("orderId", "o-1"),
                Map.of(),
                new OutboxMetadata(storedContext(), OutboxDeliveryMetadata.empty()),
                null,
                0,
                Instant.now());

        handler.publish(envelope).result();

        ArgumentCaptor<DurableMetadata> sent = ArgumentCaptor.forClass(DurableMetadata.class);
        verify(producerFactory).sendForOutbox(any(), any(), any(), any(), sent.capture(), any());
        return sent.getValue().namespaces();
    }

    @Test
    @DisplayName("without a list the signed identity stays inside the process and every other namespace is sent")
    void defaultWithholdsTheIdentitySnapshot() {
        Set<String> sent = sentNamespaces(handler(KafkaOutboxConfig.defaults()));

        assertEquals(Set.of("correlation", "localization"), sent);
    }

    @Test
    @DisplayName("a list naming the identity forwards it, and only what the list names")
    void aListNamingTheIdentityForwardsIt() {
        Set<String> sent = sentNamespaces(handler(new KafkaOutboxConfig(List.of("correlation", IDENTITY))));

        assertEquals(Set.of("correlation", IDENTITY), sent);
    }

    @Test
    @DisplayName("an empty list forwards no context")
    void anEmptyListForwardsNothing() {
        Set<String> sent = sentNamespaces(handler(new KafkaOutboxConfig(List.of())));

        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("a list replaces the default: a namespace it does not name is not sent")
    void aListReplacesTheDefault() {
        Set<String> sent = sentNamespaces(handler(new KafkaOutboxConfig(List.of("localization"))));

        assertEquals(Set.of("localization"), sent);
        assertFalse(sent.contains("correlation"));
    }

    @Test
    @DisplayName("a listed namespace no registered encoder owns fails construction, naming path and position")
    void anUnregisteredNameFailsConstruction() {
        InboxOutboxConfigurationException e = assertThrows(
                InboxOutboxConfigurationException.class,
                () -> new KafkaOutboxDestinationHandler(
                        producerFactory,
                        objectMapper,
                        new KafkaOutboxConfig(List.of("correlation", "corelation")),
                        registry("correlation")));

        assertTrue(e.getMessage().contains("inboxOutbox.kafka.outboundContextNamespaces[1]"), e.getMessage());
        assertFalse(e.getMessage().contains("corelation"), "the rejected text is never echoed");
        assertTrue(e.getMessage().contains("registered: [correlation]"), e.getMessage());
    }

    @Test
    @DisplayName("a blank or repeated name fails when the configuration is built")
    void aBlankOrRepeatedNameFailsParsing() {
        InboxOutboxConfigurationException blank = assertThrows(
                InboxOutboxConfigurationException.class, () -> new KafkaOutboxConfig(List.of("correlation", " ")));
        InboxOutboxConfigurationException repeated = assertThrows(
                InboxOutboxConfigurationException.class,
                () -> new KafkaOutboxConfig(List.of("correlation", "correlation")));

        assertTrue(blank.getMessage().contains("inboxOutbox.kafka.outboundContextNamespaces[1]"));
        assertTrue(repeated.getMessage().contains("inboxOutbox.kafka.outboundContextNamespaces[1]"));
        assertFalse(repeated.getMessage().contains("correlation"), "the repeated text is never echoed");
    }

    @Test
    @DisplayName("the configuration is read from the inboxOutbox.kafka section, and an absent section is the default")
    void readsTheKafkaSection() {
        ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());

        KafkaOutboxConfig listed = parser.parse(
                new JsonObject().put("outboundContextNamespaces", new io.vertx.core.json.JsonArray().add(IDENTITY)),
                KafkaOutboxConfig.class);
        KafkaOutboxConfig absent = parser.parse(new JsonObject(), KafkaOutboxConfig.class);

        assertEquals(List.of(IDENTITY), listed.outboundContextNamespaces());
        assertEquals(null, absent.outboundContextNamespaces());
    }
}
