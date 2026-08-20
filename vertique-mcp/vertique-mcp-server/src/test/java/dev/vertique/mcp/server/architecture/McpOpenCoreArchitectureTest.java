// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.interceptor.McpTraceContext;
import dev.vertique.mcp.lifecycle.McpAuthorizationSummary;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpResultType;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.server.McpServerConfig;
import dev.vertique.mcp.server.McpServerModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Guards the neutral open-core MCP surface against enterprise audit vocabulary. */
class McpOpenCoreArchitectureTest {
    private static final List<String> FORBIDDEN_VOCABULARY = List.of("audit", "enterprise");

    @Test
    void shouldExposeOnlyNeutralLifecycleVocabulary() {
        List<String> productionViolations = vocabularyViolations(openCoreTypes());
        List<String> syntheticViolations = vocabularyViolations(List.of(SyntheticEnterpriseAuditDependency.class));

        assertThat(productionViolations).isEmpty();
        assertThat(syntheticViolations).hasSize(1);
        assertThat(syntheticViolations.getFirst()).contains("SyntheticEnterpriseAuditDependency");
    }

    private static List<Class<?>> openCoreTypes() {
        return List.of(
                McpServerConfig.class,
                McpServerModule.class,
                McpTraceContext.class,
                McpAuthorizationSummary.class,
                McpErrorType.class,
                McpMethod.class,
                McpOutcome.class,
                McpRequestCompletedEvent.class,
                McpRequestCompletedListener.class,
                McpRequestLifecycleObserver.class,
                McpRequestObservation.class,
                McpRequestTerminalEvent.class,
                McpRequestTerminalObservation.class,
                McpResultType.class,
                McpTransportOutcome.class);
    }

    private static List<String> vocabularyViolations(List<Class<?>> types) {
        return types.stream()
                .flatMap(McpOpenCoreArchitectureTest::typeFacts)
                .filter(McpOpenCoreArchitectureTest::containsForbiddenVocabulary)
                .distinct()
                .toList();
    }

    private static Stream<String> typeFacts(Class<?> type) {
        Stream<String> constructors =
                Arrays.stream(type.getDeclaredConstructors()).flatMap(McpOpenCoreArchitectureTest::constructorFacts);
        Stream<String> fields =
                Arrays.stream(type.getDeclaredFields()).flatMap(McpOpenCoreArchitectureTest::fieldFacts);
        Stream<String> methods =
                Arrays.stream(type.getDeclaredMethods()).flatMap(McpOpenCoreArchitectureTest::methodFacts);
        Stream<String> hierarchy = Stream.concat(
                Stream.of(type.getName()),
                Stream.concat(
                        type.getGenericSuperclass() == null
                                ? Stream.empty()
                                : Stream.of(type.getGenericSuperclass().getTypeName()),
                        Arrays.stream(type.getGenericInterfaces()).map(Type::getTypeName)));
        return Stream.concat(hierarchy, Stream.concat(constructors, Stream.concat(fields, methods)));
    }

    private static Stream<String> constructorFacts(Constructor<?> constructor) {
        return Stream.concat(
                Stream.of(constructor.getName()),
                Arrays.stream(constructor.getGenericParameterTypes()).map(Type::getTypeName));
    }

    private static Stream<String> fieldFacts(Field field) {
        return Stream.of(field.getName(), field.getGenericType().getTypeName());
    }

    private static Stream<String> methodFacts(Method method) {
        return Stream.concat(
                Stream.of(method.getName(), method.getGenericReturnType().getTypeName()),
                Arrays.stream(method.getGenericParameterTypes()).map(Type::getTypeName));
    }

    private static boolean containsForbiddenVocabulary(String fact) {
        String normalized = fact.toLowerCase(Locale.ROOT);
        return FORBIDDEN_VOCABULARY.stream().anyMatch(normalized::contains);
    }

    private static final class SyntheticEnterpriseAuditDependency {}
}
