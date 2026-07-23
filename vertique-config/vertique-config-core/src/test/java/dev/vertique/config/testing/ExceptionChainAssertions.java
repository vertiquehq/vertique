// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.testing;

/**
 * Assertion helpers for walking exception cause chains in tests.
 *
 * <p>Published via the {@code vertique-config} test-jar so all config-module tests can share them
 * without copying.
 */
public final class ExceptionChainAssertions {

    private ExceptionChainAssertions() {}

    // --- Helpers ---

    /**
     * Returns {@code true} if any exception in the cause chain of {@code ex} contains {@code
     * needle} as a substring of its message.
     *
     * @param ex the root exception to search; may be {@code null} (returns {@code false})
     * @param needle the substring to search for; non-null
     * @return {@code true} if the needle is found in any message in the chain
     */
    public static boolean containsAnywhere(Throwable ex, String needle) {
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(needle)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Collects all non-null messages in the cause chain, joined by {@code "; "}.
     *
     * <p>Useful as a diagnostic string in assertion failure messages.
     *
     * @param ex the root exception; may be {@code null} (returns an empty string)
     * @return all messages joined by {@code "; "}; never {@code null}
     */
    public static String exceptionChainText(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append("; ");
            }
            current = current.getCause();
        }
        return sb.toString();
    }
}
