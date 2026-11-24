// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * INTERNAL framework seam — HTTP-runtime collaborator consumed by sibling framework modules; not
 * an application contract and outside the maturity promise. An application uses the extension
 * points and configuration this module documents and never names this type.
 *
 * <p>The grammar of a JAX-RS path-template variable and the naming rule a regex-constrained
 * template adds. The JAX-RS route registrar and the annotation processor both read it, so a
 * template the runtime rejects at startup is the same template the processor rejects at compile
 * time.
 *
 * <p>A template that carries a {@code :regex} constraint on any variable is registered as one
 * anchored regular expression in which every variable, constrained or not, becomes a named capture
 * group. A Java named group accepts only a letter followed by letters and digits, so such a template
 * cannot declare a variable named {@code user_id}, {@code user-id}, {@code user.id} or {@code 2nd}.
 * A template with no constraint is registered in Vert.x's colon form and this rule does not apply to it.
 *
 * <p>All members are static; this class is not intended to be instantiated.
 */
public final class PathTemplateVariables {

    /**
     * Matches one path variable {@code {name}} or {@code {name:regex}}. Group 1 is the variable name;
     * group 2 (optional) is the regex constraint without the leading colon. The constraint allows
     * nested braces (for example {@code \d{3}}). It is written as runs of non-brace characters with
     * possessive repetition, so a long constraint cannot overflow the stack.
     */
    public static final Pattern VARIABLE = Pattern.compile(
            "\\{\\s*(\\w[\\w.-]*)\\s*(?::\\s*((?=[^{}]|\\{[^{}]*})[^{}]*+(?:\\{[^{}]*+}[^{}]*+)*+))?\\s*}");

    private static final Pattern REGEX_GROUP_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private PathTemplateVariables() {}

    /**
     * Returns the names of the variables a regex-constrained template cannot register, in template
     * order and without duplicates. A template with no {@code :regex} constraint has none.
     *
     * @param template the JAX-RS path template, for example {@code /u/{user_id:[0-9]+}}; must not be
     *                 {@code null}
     * @return the variable names that are not a letter followed by letters and digits; empty when the
     *     template is not regex-constrained or every name is valid
     */
    public static List<String> invalidRegexVariableNames(String template) {
        var matcher = VARIABLE.matcher(template);
        List<String> names = new ArrayList<>();
        boolean constrained = false;
        while (matcher.find()) {
            constrained |= matcher.group(2) != null;
            String name = matcher.group(1);
            if (!REGEX_GROUP_NAME.matcher(name).matches() && !names.contains(name)) {
                names.add(name);
            }
        }
        return constrained ? List.copyOf(names) : List.of();
    }

    /**
     * Returns the diagnostic for a variable that a regex-constrained template cannot register.
     *
     * @param template the path template that declares the variable
     * @param name     the offending variable name
     * @return the message, naming the template, the variable and the rule
     */
    public static String invalidRegexVariableNameMessage(String template, String name) {
        return String.format(
                "Path template '%s' declares the variable '%s' in a template with a regex constraint;"
                        + " in such a template every variable becomes a named regex group, so its name must"
                        + " be a letter followed by letters and digits. Rename the variable.",
                template, name);
    }
}
