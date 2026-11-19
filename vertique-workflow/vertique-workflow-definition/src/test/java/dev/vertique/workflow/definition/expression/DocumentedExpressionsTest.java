// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compiles every decision-route {@code when:} expression that the packaged module document shows in a YAML example, so
 * an example that names a variable the expression environment does not declare fails here instead of at a reader's
 * first load.
 */
class DocumentedExpressionsTest {

    private static final String MODULE_DOCUMENT = "/META-INF/vertique/module.md";

    private static final Pattern WHEN = Pattern.compile("^\\s*-?\\s*when:\\s*\"(.+)\"\\s*$", Pattern.MULTILINE);

    private final ExpressionProfile profile = new CelExpressionProfile();

    private final ExpressionEnv env = new ExpressionEnv(Object.class, Set.of(), Set.of());

    @Test
    @DisplayName("every documented when expression compiles in the expression environment")
    void documentedWhenExpressionsCompile() throws IOException {
        List<String> documented = documentedWhenExpressions();

        assertThat(documented).as("the module document shows when expressions").isNotEmpty();
        for (String source : documented) {
            assertThat(profile.compile(source, env))
                    .as("documented expression: %s", source)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("a variable other than state or a named condition is not declared, so such an example would not load")
    void undeclaredVariableDoesNotCompile() {
        assertThatThrownBy(() -> profile.compile("state.total > 1000 && review.country == 'FI'", env))
                .isInstanceOf(ExpressionParseException.class);
    }

    private static List<String> documentedWhenExpressions() throws IOException {
        String document;
        try (InputStream in = DocumentedExpressionsTest.class.getResourceAsStream(MODULE_DOCUMENT)) {
            assertThat(in).as("%s on the classpath", MODULE_DOCUMENT).isNotNull();
            document = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> found = new ArrayList<>();
        Matcher matcher = WHEN.matcher(document);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
