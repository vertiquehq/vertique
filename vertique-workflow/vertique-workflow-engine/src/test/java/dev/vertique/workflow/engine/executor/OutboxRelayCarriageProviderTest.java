// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.workflow.engine.WorkflowEngineModule;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies how the engine module binds the identity carriage requirements of the outbox relay and of the workflow from
 * the application configuration into the typed value the startup warning receives: a requirement counts only when it
 * is {@code REQUIRED}, and an absent entry or section is not required and is not an error.
 */
class OutboxRelayCarriageProviderTest {

    /** Binds a section to a record the way the framework's parser does, leniently about the keys it does not know. */
    private static final class BindingParser implements ConfigParser {
        private final ObjectMapper mapper =
                new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        @Override
        public <T> T parse(JsonObject section, Class<T> type) {
            return mapper.convertValue(section == null ? Map.of() : section.getMap(), type);
        }

        @Override
        public <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<T> parseKeyedObject(
                JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
            throw new UnsupportedOperationException();
        }
    }

    private static JsonObject carriage(String key, String value) {
        return new JsonObject()
                .put(
                        "identity",
                        new JsonObject()
                                .put(
                                        "snapshot",
                                        new JsonObject()
                                                .put("onDegradation", "FAIL")
                                                .put("carriageRequirements", new JsonObject().put(key, value))));
    }

    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of("outbox-relay REQUIRED", carriage("outbox-relay", "REQUIRED"), true, false),
                Arguments.of("outbox-relay OPTIONAL", carriage("outbox-relay", "OPTIONAL"), false, false),
                Arguments.of("outbox-relay FORBIDDEN", carriage("outbox-relay", "FORBIDDEN"), false, false),
                Arguments.of("other kind REQUIRED, no outbox-relay entry", carriage("cron", "REQUIRED"), false, false),
                Arguments.of(
                        "empty requirements",
                        new JsonObject()
                                .put(
                                        "identity",
                                        new JsonObject()
                                                .put(
                                                        "snapshot",
                                                        new JsonObject()
                                                                .put("carriageRequirements", new JsonObject()))),
                        false,
                        false),
                Arguments.of("no snapshot section", new JsonObject().put("identity", new JsonObject()), false, false),
                Arguments.of("no identity section", new JsonObject(), false, false),
                Arguments.of("workflow REQUIRED", carriage("workflow", "REQUIRED"), false, true),
                Arguments.of("workflow OPTIONAL", carriage("workflow", "OPTIONAL"), false, false),
                Arguments.of(
                        "both REQUIRED",
                        new JsonObject()
                                .put(
                                        "identity",
                                        new JsonObject()
                                                .put(
                                                        "snapshot",
                                                        new JsonObject()
                                                                .put(
                                                                        "carriageRequirements",
                                                                        new JsonObject()
                                                                                .put("outbox-relay", "REQUIRED")
                                                                                .put("workflow", "REQUIRED")))),
                        true,
                        true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    void providerMapsTheOutboxRelayEntryToATypedValue(
            String name, JsonObject config, boolean required, boolean workflowRequired) {
        // Given an application configuration
        ConfigParser parser = new BindingParser();

        // When the engine module builds the typed value
        OutboxRelayCarriage carriage = WorkflowEngineModule.outboxRelayCarriage(config, parser);

        // Then each requirement is set only for REQUIRED, and an absent entry is not required
        assertThat(carriage.required()).as("outbox-relay").isEqualTo(required);
        assertThat(carriage.workflowRequired()).as("workflow").isEqualTo(workflowRequired);
    }
}
