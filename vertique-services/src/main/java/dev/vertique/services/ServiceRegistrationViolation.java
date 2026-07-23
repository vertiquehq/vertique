// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

/**
 * Describes a single validation problem found during service registration.
 *
 * @param contract the contract interface, or {@code null} if the violation is at the impl level
 * @param method the method name, or {@code null} if the violation is at the type level
 * @param message human-readable description of the problem
 */
public record ServiceRegistrationViolation(Class<?> contract, String method, String message) {

    /**
     * Creates a type-level violation (no specific method).
     *
     * @param contract the contract interface
     * @param message human-readable description of the problem
     * @return a new type-level violation
     */
    public static ServiceRegistrationViolation ofType(Class<?> contract, String message) {
        return new ServiceRegistrationViolation(contract, null, message);
    }

    /**
     * Creates a method-level violation.
     *
     * @param contract the contract interface
     * @param method the method name
     * @param message human-readable description of the problem
     * @return a new method-level violation
     */
    public static ServiceRegistrationViolation ofMethod(Class<?> contract, String method, String message) {
        return new ServiceRegistrationViolation(contract, method, message);
    }

    /**
     * Creates an impl-level violation (no contract resolved yet).
     *
     * @param message human-readable description of the problem
     * @return a new impl-level violation
     */
    public static ServiceRegistrationViolation ofImpl(String message) {
        return new ServiceRegistrationViolation(null, null, message);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (contract != null) {
            sb.append(contract.getSimpleName());
        } else {
            sb.append("<unknown>");
        }
        if (method != null) {
            sb.append('.').append(method).append("()");
        }
        sb.append(": ").append(message);
        return sb.toString();
    }
}
