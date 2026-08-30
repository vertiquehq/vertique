// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifies that MCP profile validation occurs during composition regardless of server activity. */
class McpJsonProfileDefaultValidatorTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("validProfileRows")
    @DisplayName("inherits blank and accepts registered profile ids")
    void shouldAcceptBlankOrRegisteredConfiguredProfile(
            String row, boolean enabled, boolean shadowedByMethodAnnotation, String profileId) {
        JsonMapperProfileRegistry registry = mock(JsonMapperProfileRegistry.class);
        McpServerConfig config = McpServerConfig.builder()
                .enabled(enabled)
                .jsonProfile(profileId)
                .build();

        assertThatCode(() -> new McpJsonProfileDefaultValidator(config, registry))
                .doesNotThrowAnyException();
        verify(registry).validateConfigured(profileId);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownProfileRows")
    @DisplayName("rejects an unknown configured profile even when MCP is inert")
    void shouldFailUnknownConfiguredProfileEvenWhenDisabledOrShadowed(
            String row, boolean enabled, boolean shadowedByMethodAnnotation) {
        JsonMapperProfileRegistry registry = mock(JsonMapperProfileRegistry.class);
        doThrow(new JsonProfileConfigurationException("Unknown JSON profile id 'missing'"))
                .when(registry)
                .validateConfigured("missing");
        McpServerConfig config = McpServerConfig.builder()
                .enabled(enabled)
                .jsonProfile("missing")
                .build();

        assertThatThrownBy(() -> new McpJsonProfileDefaultValidator(config, registry))
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("missing");
        verify(registry).validateConfigured("missing");
    }

    private static Stream<Arguments> validProfileRows() {
        return Stream.of(
                Arguments.of("blank profile inherits while MCP is enabled", true, false, ""),
                Arguments.of("registered strict profile resolves while MCP is disabled", false, false, "strict"),
                Arguments.of("registered strict profile resolves despite method shadowing", true, true, "strict"));
    }

    private static Stream<Arguments> unknownProfileRows() {
        return Stream.of(
                Arguments.of("unknown profile fails while MCP is enabled", true, false),
                Arguments.of("unknown profile fails while MCP is disabled", false, false),
                Arguments.of("unknown profile fails despite method shadowing", true, true));
    }
}
