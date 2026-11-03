// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.admin;

import dev.vertique.examples.workflow.load.config.LoadArm;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parsed command line of {@link LoadAdmin}.
 *
 * <pre>{@code
 * migrate               [--conf <json-or-file>]
 * triggers              [--conf <json-or-file>]
 * arm <arm>             [--conf <json-or-file>]
 * arm-config <arm>
 * }</pre>
 *
 * @param command the command to run
 * @param arm the arm for {@code arm} and {@code arm-config}, otherwise {@code null}
 * @param conf the value of {@code --conf}: a JSON object or the path of a JSON file, otherwise {@code null}
 */
record AdminArguments(
        Command command, @Nullable LoadArm arm, @Nullable String conf) {

    /** The commands of the admin entry point. */
    enum Command {

        /** Runs every migration once, each with its own history table. */
        MIGRATE("migrate"),

        /** Prints the state of the two notification triggers. */
        TRIGGERS("triggers"),

        /** Puts the two triggers into the state of an arm; refuses while a listener is connected. */
        ARM("arm"),

        /** Prints the configuration an arm needs on every node, without touching the database. */
        ARM_CONFIG("arm-config");

        private final String word;

        Command(String word) {
            this.word = word;
        }

        String word() {
            return word;
        }

        boolean takesArm() {
            return this == ARM || this == ARM_CONFIG;
        }

        boolean takesConf() {
            return this != ARM_CONFIG;
        }
    }

    /** Usage text for error messages. */
    static final String USAGE = "usage: migrate | triggers | arm <arm> | arm-config <arm>   [--conf <json-or-file>]"
            + "   (arms: " + List.of(LoadArm.values()) + ")";

    /**
     * Parses a command line.
     *
     * @param args the arguments after the program name
     * @return the parsed command
     * @throws IllegalArgumentException with the usage text when the command or an option is unknown, a required
     *     value is missing, or an argument is left over
     */
    static AdminArguments parse(String[] args) {
        List<String> positional = new ArrayList<>();
        String conf = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--conf")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--conf needs a value; " + USAGE);
                }
                conf = args[++i];
            } else if (arg.startsWith("--conf=")) {
                conf = arg.substring("--conf=".length());
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option " + arg + "; " + USAGE);
            } else {
                positional.add(arg);
            }
        }
        if (positional.isEmpty()) {
            throw new IllegalArgumentException("A command is required; " + USAGE);
        }
        Command command = commandOf(positional.get(0));
        int expected = command.takesArm() ? 2 : 1;
        if (positional.size() != expected) {
            throw new IllegalArgumentException(
                    positional.size() < expected
                            ? "Command " + command.word() + " needs an arm; " + USAGE
                            : "Unexpected argument " + positional.get(expected) + "; " + USAGE);
        }
        if (conf != null && !command.takesConf()) {
            throw new IllegalArgumentException("Command " + command.word() + " takes no --conf; " + USAGE);
        }
        LoadArm arm = command.takesArm() ? LoadArm.parse(positional.get(1)) : null;
        return new AdminArguments(command, arm, conf);
    }

    private static Command commandOf(String word) {
        String normalized = word.toLowerCase(Locale.ROOT);
        for (Command command : Command.values()) {
            if (command.word().equals(normalized)) {
                return command;
            }
        }
        throw new IllegalArgumentException("Unknown command " + word + "; " + USAGE);
    }
}
