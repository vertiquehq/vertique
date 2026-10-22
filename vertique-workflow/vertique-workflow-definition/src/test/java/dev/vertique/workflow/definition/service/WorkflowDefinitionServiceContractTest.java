// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins the exact asynchronous service consumer contract and waived compatibility removals. */
class WorkflowDefinitionServiceContractTest {

    @Test
    @DisplayName("service operations use the Future contract and the eight old declarations are absent")
    void replacesSynchronousDeclarationsWithFutureOperations() throws NoSuchMethodException {
        assertFutureMethod(
                WorkflowDefinitionService.class,
                "load",
                new Class<?>[] {String.class, byte[].class, DocumentFormat.class, SourceMetadata.class},
                CompiledDefinitionRef.class);
        assertFutureMethod(
                WorkflowDefinitionService.class, "publish", new Class<?>[] {DefinitionKey.class}, Void.class);
        assertFutureMethod(
                WorkflowDefinitionService.class, "list", new Class<?>[] {DefinitionFilter.class}, List.class);
        assertFutureMethod(
                WorkflowDefinitionService.class,
                "sourceMetadata",
                new Class<?>[] {DefinitionKey.class},
                Optional.class);

        assertFutureMethod(
                DefaultWorkflowDefinitionService.class,
                "load",
                new Class<?>[] {String.class, byte[].class, DocumentFormat.class, SourceMetadata.class},
                CompiledDefinitionRef.class);
        assertFutureMethod(
                DefaultWorkflowDefinitionService.class, "publish", new Class<?>[] {DefinitionKey.class}, Void.class);
        assertFutureMethod(
                DefaultWorkflowDefinitionService.class, "list", new Class<?>[] {DefinitionFilter.class}, List.class);
        assertFutureMethod(
                DefaultWorkflowDefinitionService.class,
                "sourceMetadata",
                new Class<?>[] {DefinitionKey.class},
                Optional.class);

        assertAbsent(WorkflowDefinitionService.class, "load", byte[].class, DocumentFormat.class, SourceMetadata.class);
        assertAbsent(WorkflowDefinitionService.class, "activate", String.class, long.class);
        assertAbsent(WorkflowDefinitionService.class, "activate", DefinitionKey.class);
        assertAbsent(WorkflowDefinitionService.class, "list");
        assertAbsent(WorkflowDefinitionService.class, "sourceMetadata", String.class, long.class);
        assertAbsent(
                DefaultWorkflowDefinitionService.class,
                "load",
                byte[].class,
                DocumentFormat.class,
                SourceMetadata.class);
        assertAbsent(DefaultWorkflowDefinitionService.class, "activate", String.class, long.class);
        assertAbsent(DefaultWorkflowDefinitionService.class, "activate", DefinitionKey.class);
        assertAbsent(DefaultWorkflowDefinitionService.class, "list");
        assertAbsent(DefaultWorkflowDefinitionService.class, "sourceMetadata", String.class, long.class);
    }

    private static void assertFutureMethod(Class<?> owner, String name, Class<?>[] parameters, Class<?> resultType)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        assertThat(method.getReturnType()).isEqualTo(Future.class);
        Type genericReturn = method.getGenericReturnType();
        assertThat(genericReturn).isInstanceOf(ParameterizedType.class);
        Type genericArgument = ((ParameterizedType) genericReturn).getActualTypeArguments()[0];
        if (resultType == List.class) {
            assertThat(genericArgument.getTypeName())
                    .isEqualTo("java.util.List<" + WorkflowDefinitionRecord.class.getName() + ">");
        } else if (resultType == Optional.class) {
            assertThat(genericArgument.getTypeName())
                    .isEqualTo("java.util.Optional<" + SourceMetadata.class.getName() + ">");
        } else {
            assertThat(genericArgument).isEqualTo(resultType);
        }
    }

    private static void assertAbsent(Class<?> owner, String name, Class<?>... parameters) {
        assertThat(owner.getDeclaredMethods())
                .as("removed declaration %s.%s%s", owner.getName(), name, List.of(parameters))
                .noneMatch(method -> method.getName().equals(name)
                        && java.util.Arrays.equals(method.getParameterTypes(), parameters));
    }
}
