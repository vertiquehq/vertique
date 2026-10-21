// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.WorkflowType;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import io.vertx.core.Future;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a document service load which names an unknown type repeats the name in the error only when the name
 * is made of letters, digits, underscore, dot, dollar sign and hyphen, so a tenant-supplied hostile value never
 * reaches a log line.
 *
 * <p>The hostile names are built at run time from code points, so no invisible or direction-changing character
 * appears in this source file.
 */
class UnknownNameEchoTest {

    private static final String RIGHT_TO_LEFT_OVERRIDE = String.valueOf(Character.toChars(0x202E));
    private static final String ZERO_WIDTH_SPACE = String.valueOf(Character.toChars(0x200B));

    // --- Fixture types ---

    @WorkflowType("echo.state")
    record EchoState(String id) {}

    record EchoStart(String id) {}

    @WorkflowContract("echo.contract")
    interface EchoContract {}

    @Module
    static class CatalogModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog catalog() {
            return () -> List.of(
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, "echo.contract", EchoContract.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, "echo.start", EchoStart.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, "echo.state", EchoState.class));
        }

        @Provides
        @IntoSet
        static StartStateMapperContributor mapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "echo.init", EchoStart.class, EchoState.class, start -> new EchoState(start.id())));
        }
    }

    @Singleton
    @Component(modules = {WorkflowDefinitionModule.class, WorkflowCoreModule.class, CatalogModule.class})
    interface EchoComponent {

        WorkflowDefinitionService workflowDefinitionService();
    }

    private static Future<CompiledDefinitionRef> load(EchoComponent component, String stateType) {
        String document = """
                definitionId: echo
                definitionVersion: 1
                contract: echo.contract
                stateType: "%s"
                startPayloadType: echo.start
                initialStateMapper: echo.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """.formatted(stateType);
        return component
                .workflowDefinitionService()
                .load(
                        WorkflowScopes.DEFAULT,
                        document.getBytes(StandardCharsets.UTF_8),
                        DocumentFormat.YAML,
                        new SourceMetadata("test", "test://echo", null, null, null, Instant.now()));
    }

    @Test
    @DisplayName("an unknown state type with a direction override or zero-width characters is not repeated")
    void hostileStateTypeIsNotRepeated() {
        for (String hostile : new String[] {
            "evil" + RIGHT_TO_LEFT_OVERRIDE + "name", "evil" + ZERO_WIDTH_SPACE + "name", "has evil space"
        }) {
            EchoComponent component = DaggerUnknownNameEchoTest_EchoComponent.create();

            Future<CompiledDefinitionRef> loading = load(component, hostile);

            assertThatThrownBy(() -> ServiceTestHelpers.await(loading))
                    .isInstanceOf(WorkflowUnknownTypeNameException.class)
                    .hasMessageContaining("stateType")
                    .message()
                    .doesNotContain("evil")
                    .doesNotContain(RIGHT_TO_LEFT_OVERRIDE)
                    .doesNotContain(ZERO_WIDTH_SPACE);
        }
    }

    @Test
    @DisplayName("an unknown state type matching the allowed set is repeated with its field")
    void conformingStateTypeIsRepeated() {
        EchoComponent component = DaggerUnknownNameEchoTest_EchoComponent.create();

        Future<CompiledDefinitionRef> loading = load(component, "com.acme.Missing$Inner-1");

        assertThatThrownBy(() -> ServiceTestHelpers.await(loading))
                .isInstanceOf(WorkflowUnknownTypeNameException.class)
                .hasMessageContaining("stateType")
                .hasMessageContaining("com.acme.Missing$Inner-1");
    }
}
