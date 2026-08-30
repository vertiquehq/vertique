// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import io.vertx.core.Vertx;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T010 TP-001 — the frozen registry and startup-validation contract matrix.
 *
 * <p>Every row composes a server exactly once through {@link McpSchemaStartupTestFixture#compose},
 * which runs the owned production sequence — {@link McpToolRegistry#build}, then
 * {@link McpServerConfigValidator#validate(McpServerConfig, Set, McpToolRegistry)} — against a real
 * {@link io.vertx.ext.web.Router}, and adds the fixture's one placeholder "mount" route only after
 * both steps succeed. That lets every row observe the actual mounted-route count rather than merely
 * catching an exception, so a row cannot pass by mounting first and failing after.
 *
 * <p><strong>Deviation from the task's stated test location:</strong> this class lives in {@code
 * dev.vertique.mcp.server}, not {@code dev.vertique.mcp.server.runtime}, because {@link
 * McpToolRegistry} and {@link McpServerConfigValidator} are package-private per the frozen artifact
 * inventory and are not consumer-facing public API — package-private access requires the exact same
 * package as the types under test, exactly as {@code McpValidatorConcurrencyTest} recorded for T009.
 */
@DisplayName("MCP tool registry and startup validation — T010 contract matrix")
class McpSchemaStartupTest {

    private static final String DUPLICATE_NAMES_ROW = "shouldFailBeforeMountForDuplicateToolNames";
    private static final String UNREPRESENTABLE_MAPPING_ROW = "shouldFailBeforeMountForUnrepresentableMappingContract";
    private static final String REGISTRY_VISIBILITY_ROW =
            "shouldAllowUnconfiguredPublicOrDenyAllRegistryAndRejectUnconfiguredRestrictedRegistry";
    private static final String IMMUTABLE_ORDERED_REGISTRY_ROW = "shouldBuildAnImmutableGlobalNameOrderedRegistry";

    private final Vertx vertx = Vertx.vertx();

    /** Closes the owned {@link Vertx}, waiting for its teardown to settle. */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    private static Stream<String> t010ContractRows() {
        return Stream.of(
                DUPLICATE_NAMES_ROW,
                UNREPRESENTABLE_MAPPING_ROW,
                REGISTRY_VISIBILITY_ROW,
                IMMUTABLE_ORDERED_REGISTRY_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t010ContractRows")
    @DisplayName("enforces the T010 contract matrix")
    void shouldEnforceT010ContractMatrix(String row) {
        switch (row) {
            case DUPLICATE_NAMES_ROW -> shouldFailBeforeMountForDuplicateToolNames();
            case UNREPRESENTABLE_MAPPING_ROW -> shouldFailBeforeMountForUnrepresentableMappingContract();
            case REGISTRY_VISIBILITY_ROW ->
                shouldAllowUnconfiguredPublicOrDenyAllRegistryAndRejectUnconfiguredRestrictedRegistry();
            case IMMUTABLE_ORDERED_REGISTRY_ROW -> shouldBuildAnImmutableGlobalNameOrderedRegistry();
            default -> fail("unknown T010 contract matrix row: " + row);
        }
    }

    // --- Row 1: two contributions publishing the same tool name fail before any route mounts ---

    private void shouldFailBeforeMountForDuplicateToolNames() {
        McpSchemaStartupTestFixture.ComposeResult result = McpSchemaStartupTestFixture.compose(
                vertx,
                McpSchemaStartupTestFixture.twoInvokersSharingOneName(),
                McpSchemaStartupTestFixture.enabledPublicOnlyConfig(),
                Set.of());

        assertThat(result.startupErrorCount())
                .as("exactly one bounded startup error for the duplicate tool name")
                .isEqualTo(1);
        assertThat(result.mountedRouteCount())
                .as("no route mounts before the duplicate name is detected")
                .isZero();
    }

    // --- Row 2: a tool whose schema has no representable/compilable mapping fails before any mount ---

    private void shouldFailBeforeMountForUnrepresentableMappingContract() {
        McpSchemaStartupTestFixture.ComposeResult result = McpSchemaStartupTestFixture.compose(
                vertx,
                Set.of(McpSchemaStartupTestFixture.invokerWithUncompilableSchema("broken.tool")),
                McpSchemaStartupTestFixture.enabledPublicOnlyConfig(),
                Set.of());

        assertThat(result.startupErrorCount())
                .as("exactly one bounded startup error for the uncompilable schema/mapping contract")
                .isEqualTo(1);
        assertThat(result.mountedRouteCount())
                .as("no route mounts before the uncompilable schema is detected")
                .isZero();
    }

    // --- Row 3: registry visibility — unconfigured public/deny-all allowed, restricted rejected ---

    private void shouldAllowUnconfiguredPublicOrDenyAllRegistryAndRejectUnconfiguredRestrictedRegistry() {
        McpServerConfig noSchemeConfig = McpSchemaStartupTestFixture.enabledPublicOnlyConfig();

        McpSchemaStartupTestFixture.ComposeResult publicAndDenyAll = McpSchemaStartupTestFixture.compose(
                vertx, McpSchemaStartupTestFixture.publicAndDenyAllInvokers(), noSchemeConfig, Set.of());
        assertThat(publicAndDenyAll.startupErrorCount())
                .as("an unconfigured public/deny-all registry must be allowed")
                .isZero();
        assertThat(publicAndDenyAll.mountedRouteCount())
                .as("...and the mount must proceed")
                .isEqualTo(1);

        McpSchemaStartupTestFixture.ComposeResult restricted = McpSchemaStartupTestFixture.compose(
                vertx, McpSchemaStartupTestFixture.restrictedInvokers(), noSchemeConfig, Set.of());
        assertThat(restricted.startupErrorCount())
                .as("an unconfigured restricted registry must be rejected")
                .isEqualTo(1);
        assertThat(restricted.mountedRouteCount())
                .as("...before any route mounts")
                .isZero();
    }

    // --- Row 4: the valid registry is immutable, global-name-ordered, and digest-stable ---

    private void shouldBuildAnImmutableGlobalNameOrderedRegistry() {
        // Two independent builds of the same composition, contributed in the same non-sorted order.
        McpToolRegistry first = McpToolRegistry.build(McpSchemaStartupTestFixture.twoToolsReversedInsertionOrder());
        McpToolRegistry second = McpToolRegistry.build(McpSchemaStartupTestFixture.twoToolsReversedInsertionOrder());

        assertThat(first.descriptorsByName().keySet())
                .as("global name order must hold regardless of the reversed contribution order")
                .containsExactly("aaa.tool", "zzz.tool");

        assertThatThrownBy(() -> first.descriptorsByName().put("late.tool", null))
                .as("the built registry is immutable")
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(first.digest())
                .as("two independent builds of the same composition must produce the same stable digest")
                .isEqualTo(second.digest());
    }
}
