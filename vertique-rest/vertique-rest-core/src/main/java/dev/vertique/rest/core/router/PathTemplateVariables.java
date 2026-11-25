// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
 * <p>Two more rules apply to every template, constrained or not: a variable name may appear only
 * once, and a {@code :regex} constraint must be a valid regular expression. Both fail startup (and
 * the annotation processor) with a message that names the resource method and the variable, not a
 * raw pattern error.
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
     * Returns the names declared more than once in a template, in template order and without
     * repeats. Two variables of one name cannot both bind: a regex-constrained template makes each a
     * named group of the same name, and a plain template stores one value per name.
     *
     * @param template the JAX-RS path template; must not be {@code null}
     * @return the repeated variable names; empty when every name is distinct
     */
    public static List<String> duplicateVariableNames(String template) {
        var matcher = VARIABLE.matcher(template);
        List<String> seen = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (seen.contains(name)) {
                if (!duplicates.contains(name)) {
                    duplicates.add(name);
                }
            } else {
                seen.add(name);
            }
        }
        return List.copyOf(duplicates);
    }

    /**
     * Returns the names of the variables whose {@code :regex} constraint is not a valid regular
     * expression, in template order and without repeats. Each constraint is compiled on its own, so a
     * failure names the variable that declares it.
     *
     * @param template the JAX-RS path template; must not be {@code null}
     * @return the variable names with a malformed constraint; empty when every constraint compiles
     */
    public static List<String> malformedConstraintNames(String template) {
        var matcher = VARIABLE.matcher(template);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            String constraint = matcher.group(2);
            String name = matcher.group(1);
            if (constraint != null && !compilesInGroup(name, constraint.trim()) && !names.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * Compiles a constraint the way the route is built: inside the named group of its own variable.
     * That catches what compiling it alone misses, such as {@code \Q} with no {@code \E}, an inline
     * {@code (?x)} comment that swallows the closing parenthesis, and a nested group that reuses the
     * variable's name. A name a group cannot carry is reported separately, so a neutral group name
     * stands in for it here.
     */
    private static boolean compilesInGroup(String name, String constraint) {
        String group = REGEX_GROUP_NAME.matcher(name).matches() ? name : "g";
        try {
            Pattern.compile("(?<" + group + ">" + constraint + ")");
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Builds the anchored regular expression a regex-constrained template is registered with. A
     * constrained variable {@code {name:regex}} becomes the named group {@code (?<name>regex)}; an
     * unconstrained variable {@code {name}} in the same template becomes {@code (?<name>[^/]+)}.
     * Literal text between variables is regex-quoted, and the result is anchored with {@code ^…$}.
     * The route registrar and the problem check build the pattern here, so both see one pattern.
     *
     * @param template  the JAX-RS path template; must not be {@code null}
     * @param capturing {@code false} to emit every variable as a non-capturing group
     * @return the anchored regex, with named capture groups when {@code capturing}
     */
    public static String anchoredRegex(String template, boolean capturing) {
        var matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder("^");
        int last = 0;
        while (matcher.find()) {
            out.append(Pattern.quote(template.substring(last, matcher.start())));
            String name = matcher.group(1);
            String constraint = matcher.group(2);
            // A variant keeps its alternative text exactly (a trailing space is part of it).
            String body = constraint != null ? (capturing ? constraint.trim() : constraint) : "[^/]+";
            out.append(capturing ? "(?<" + name + ">" : "(?:").append(body).append(')');
            last = matcher.end();
        }
        out.append(Pattern.quote(template.substring(last)));
        out.append('$');
        return out.toString();
    }

    /** Whether the regular expression compiles. */
    private static boolean compiles(String regex) {
        try {
            Pattern.compile(regex);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private static boolean hasConstraint(String template) {
        var matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            if (matcher.group(2) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns every problem that stops a template from being registered as a route, in a fixed
     * order: names a regex group cannot carry, repeated names, then malformed constraints. When none of
     * those applies, the assembled route pattern is compiled as the last check.
     *
     * @param template the JAX-RS path template, for example {@code /u/{user_id:[0-9]+}}; must not be
     *                 {@code null}
     * @return the problems; empty when the template is registrable as far as its variables go
     */
    public static List<Problem> problems(String template) {
        List<Problem> problems = new ArrayList<>();
        invalidRegexVariableNames(template).forEach(name -> problems.add(new Problem(Kind.INVALID_NAME, name)));
        duplicateVariableNames(template).forEach(name -> problems.add(new Problem(Kind.DUPLICATE_NAME, name)));
        malformedConstraintNames(template).forEach(name -> problems.add(new Problem(Kind.MALFORMED_CONSTRAINT, name)));
        if (problems.isEmpty() && hasConstraint(template) && !compiles(anchoredRegex(template, true))) {
            // Last resort: every variable passed alone, but the assembled route pattern does not compile
            // (for example a group in one constraint reuses another variable's name).
            problems.add(new Problem(Kind.UNREGISTRABLE, ""));
        }
        return List.copyOf(problems);
    }

    /** What is wrong with a template variable. */
    public enum Kind {
        /** The name is not a letter followed by letters and digits, in a template with a regex constraint. */
        INVALID_NAME,
        /** The name is declared more than once in the template. */
        DUPLICATE_NAME,
        /** The variable's {@code :regex} constraint is not a valid regular expression. */
        MALFORMED_CONSTRAINT,
        /** Each variable is fine alone, but the assembled route pattern does not compile. */
        UNREGISTRABLE
    }

    /**
     * One problem with one variable of a template.
     *
     * @param kind     what is wrong
     * @param variable the variable's name; empty for {@link Kind#UNREGISTRABLE}, which concerns the
     *                 whole template
     */
    public record Problem(Kind kind, String variable) {}

    /**
     * Returns the diagnostic for a template problem. It names the resource method, the template
     * and, except for {@link Kind#UNREGISTRABLE}, the variable. It never carries a raw pattern error.
     *
     * @param owner    the resource method that declares the template, for example {@code Orders.get()}
     * @param template the combined path template
     * @param problem  the problem to describe
     * @return the message
     */
    public static String message(String owner, String template, Problem problem) {
        String name = problem.variable();
        return switch (problem.kind()) {
            case INVALID_NAME ->
                String.format(
                        "Resource method %s: path template '%s' declares the variable '%s' in a template with a"
                                + " regex constraint; in such a template every variable becomes a named regex group,"
                                + " so its name must be a letter followed by letters and digits. Rename the variable.",
                        owner, template, name);
            case DUPLICATE_NAME ->
                String.format(
                        "Resource method %s: path template '%s' declares the variable '%s' more than once;"
                                + " each variable needs its own name. Rename one of them.",
                        owner, template, name);
            case UNREGISTRABLE -> unregistrableMessage(owner, template);
            case MALFORMED_CONSTRAINT ->
                String.format(
                        "Resource method %s: path template '%s' declares the variable '%s' with a regex"
                                + " constraint that is not a valid regular expression. Fix the constraint.",
                        owner, template, name);
        };
    }

    /**
     * Returns the diagnostic for a template whose variables each pass yet whose assembled route
     * pattern does not compile.
     *
     * @param owner    the resource method that declares the template
     * @param template the combined path template
     * @return the message, naming the method and the template
     */
    public static String unregistrableMessage(String owner, String template) {
        return String.format(
                "Resource method %s: path template '%s' cannot be compiled into a route pattern;"
                        + " check its regex constraints.",
                owner, template);
    }
}
