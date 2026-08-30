// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T008 TP-002 — generated carriers materialize through the effective JSON profile mapper.
 *
 * <p>Given a composed server exposing one record-parameter tool ({@code identity.register}'s
 * address carrier) and one {@code Optional<T>} tool ({@code identity.register}'s nickname carrier)
 * under a real {@link dev.vertique.json.DefaultJsonMapperProfileRegistry} and a registered {@code
 * strict} profile — with the address carrier resolved through {@link McpJsonProfileResolver}'s
 * unconfigured tail (the {@code vertique} profile, issue #440) and the nickname carrier bound to the
 * selected profile ({@code strict} in the Given, swapped to {@code vertx} by the sensitivity
 * mutation).
 *
 * <p>Both carriers are hand-authored to the exact generated shape T008's emitter produces: a
 * private record whose sole component is named with the collision-safe positional identifier
 * ({@code argument0}) and carries {@code @JsonProperty(externalName)} — proven directly against
 * {@code McpToolInvokerEmitter}'s output by {@code McpToolProcessorCompileTest}. This test proves the
 * runtime half: that shape actually materializes through {@link McpToolRuntime#materializeArguments}
 * bound to the effective profile's mapper.
 */
@DisplayName("MCP generated input carriers — T008 TP-002")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpGeneratedInputCarrierIT {

    /** The selected profile for the {@code Optional<T>} carrier. Sensitivity swaps this to "vertx". */
    private static final String SELECTED_PROFILE = "strict";

    @Test
    @DisplayName("shouldMaterializeCarriersThroughTheEffectiveMapper")
    void shouldMaterializeCarriersThroughTheEffectiveMapper() {
        // --- Given: a composed server, the resolver's unconfigured tail, and a registered strict profile ---
        McpJsonProfileResolver resolver = McpGeneratedInputCarrierITFixture.resolver();
        JsonMapperProfile addressEffectiveProfile = resolver.resolve(null);
        JsonMapperProfile nicknameEffectiveProfile = resolver.resolve(JsonProfileId.of(SELECTED_PROFILE));
        McpToolRuntime<AddressToolInput> addressRuntime =
                McpGeneratedInputCarrierITFixture.runtime(addressEffectiveProfile, AddressToolInput.class);
        McpToolRuntime<NicknameToolInput> nicknameRuntime =
                McpGeneratedInputCarrierITFixture.runtime(nicknameEffectiveProfile, NicknameToolInput.class);
        List<McpToolRuntime<?>> composedTools = List.of(addressRuntime, nicknameRuntime);

        Map<String, Object> addressArguments = Map.of("addr-info", Map.of("city", "Helsinki", "zip", "00100"));
        Map<String, Object> absentNicknameArguments = Map.of();
        Map<String, Object> explicitNullNicknameArguments = new HashMap<>();
        explicitNullNicknameArguments.put("nick-name", null);

        // --- When: materialize one argument map per tool through the generated carrier exactly once ---
        AddressToolInput materializedAddress = addressRuntime.materializeArguments(addressArguments);
        NicknameToolInput materializedAbsentNickname = nicknameRuntime.materializeArguments(absentNicknameArguments);
        NicknameToolInput materializedExplicitNullNickname =
                nicknameRuntime.materializeArguments(explicitNullNicknameArguments);

        // --- Then: exactly two tools compose, regardless of which mutation is active ---
        assertThat(composedTools)
                .as("the tool count stays fixed across the sensitivity mutation")
                .hasSize(2);

        // --- Then: the record-parameter carrier materializes via the effective (vertique) mapper, and
        // the external protocol name "addr-info" maps to the collision-safe internal component argument0 ---
        assertThat(materializedAddress.argument0())
                .as("the external protocol name maps to the collision-safe internal component")
                .isEqualTo(new Address("Helsinki", "00100"));

        // --- Then: an ABSENT key resolves to Optional.empty() ---
        assertThat(materializedAbsentNickname.argument0())
                .as("an absent key resolves to Optional.empty()")
                .isEqualTo(Optional.empty());

        // --- Then (decisive): a PRESENT key carrying an explicit wire null resolves to Optional.empty()
        // too — distinct from the absent-key case above, and this is the assertion the sensitivity
        // mutation (selected profile strict -> vertx) must flip ---
        assertThat(materializedExplicitNullNickname.argument0())
                .as("an explicit wire null resolves to Optional.empty() under the effective mapper")
                .isEqualTo(Optional.empty());
    }

    // --- Generated-shaped fixtures (the exact carrier shape McpToolInvokerEmitter emits) ---

    /** The record-parameter tool's DTO parameter type. */
    private record Address(String city, String zip) {}

    /** The generated-shaped carrier for the record-parameter tool. */
    private record AddressToolInput(
            @JsonProperty("addr-info") Address argument0) {}

    /** The generated-shaped carrier for the {@code Optional<T>} tool. */
    private record NicknameToolInput(
            @JsonProperty("nick-name") Optional<String> argument0) {}
}
