// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.workflow.postgresql.registry.WorkflowDefinitionSqlObserver.Execution;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies the statements an observed pool saw while an application graph started.
 *
 * <p>A read of the definition table or of the activation table is bounded when it selects only the default scope,
 * only {@code PUBLISHING} rows, or the rows of one definition id. The classification looks at the values bound to the
 * statement's parameters as well as at its text, because the store issues one parameterised list statement for every
 * filter: the same text selects one scope or all of them.
 */
final class StartupStatements {

    private static final Pattern TABLES =
            Pattern.compile("\\bworkflow_definitions\\b|\\bworkflow_definition_activations\\b");
    private static final Pattern SCOPE_EQUALS_PARAMETER = Pattern.compile("(?<![a-z_])scope_key\\s*=\\s*\\$(\\d+)");
    private static final Pattern SCOPE_EQUALS_DEFAULT = Pattern.compile("(?<![a-z_])scope_key\\s*=\\s*'default'");
    private static final Pattern ID_EQUALS_PARAMETER = Pattern.compile("(?<![a-z_])definition_id\\s*=\\s*\\$(\\d+)");
    private static final Pattern STATUS_IN_PARAMETER = Pattern.compile("(?<![a-z_])status\\s*=\\s*any\\(\\s*\\$(\\d+)");
    private static final Pattern STATUS_IS_PUBLISHING = Pattern.compile("(?<![a-z_])status\\s*=\\s*'publishing'");

    private StartupStatements() {}

    /** Every executed read of the definition table or of the activation table, bounded or not. */
    static List<Execution> definitionReads(WorkflowDefinitionSqlObserver observer) {
        return observer.executions().stream()
                .filter(StartupStatements::readsDefinitionTables)
                .toList();
    }

    /**
     * The reads of the definition table or of the activation table that are not bounded to the default scope, to
     * {@code PUBLISHING} rows or to one definition id: the reads whose size grows with the rows of other scopes.
     */
    static List<Execution> scopedReads(WorkflowDefinitionSqlObserver observer) {
        return definitionReads(observer).stream()
                .filter(execution -> !isBounded(execution))
                .toList();
    }

    private static boolean readsDefinitionTables(Execution execution) {
        String sql = normalized(execution.sql());
        return (sql.startsWith("select") || sql.startsWith("with"))
                && TABLES.matcher(sql).find();
    }

    private static boolean isBounded(Execution execution) {
        String sql = normalized(execution.sql());
        List<Object> parameters = execution.parameters();
        if (SCOPE_EQUALS_DEFAULT.matcher(sql).find()
                || STATUS_IS_PUBLISHING.matcher(sql).find()) return true;
        if (anyParameter(SCOPE_EQUALS_PARAMETER, sql, parameters, "default"::equals)) return true;
        if (anyParameter(ID_EQUALS_PARAMETER, sql, parameters, value -> value instanceof String)) return true;
        return anyParameter(STATUS_IN_PARAMETER, sql, parameters, value -> List.of("PUBLISHING")
                .equals(value));
    }

    private static boolean anyParameter(
            Pattern placeholder, String sql, List<Object> parameters, java.util.function.Predicate<Object> accepted) {
        Matcher matcher = placeholder.matcher(sql);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1)) - 1;
            if (index < parameters.size() && accepted.test(parameters.get(index))) return true;
        }
        return false;
    }

    private static String normalized(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
