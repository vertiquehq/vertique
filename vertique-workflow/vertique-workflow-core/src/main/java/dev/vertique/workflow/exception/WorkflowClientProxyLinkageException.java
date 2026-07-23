// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a generated {@code {Contract}_WorkflowClientProxy} class is present on the classpath
 * but cannot be loaded or instantiated by {@code WorkflowClientFactory} — i.e. the class was found
 * by name yet is structurally broken (incompatible constructor, a {@code LinkageError}, or a
 * reflective instantiation failure).
 *
 * <p>This is deliberately distinct from a missing generated proxy: when the companion class is
 * <em>absent</em> ({@code ClassNotFoundException}), the factory silently falls back to the reflective
 * JDK dynamic proxy. A proxy that is present-but-broken instead indicates a build/codegen
 * inconsistency (e.g. a stale generated class after an annotation change), which must fail loudly
 * rather than be masked by the fallback path. See ADR-0073.
 */
public class WorkflowClientProxyLinkageException extends WorkflowConfigurationException {

    /**
     * Creates a new {@code WorkflowClientProxyLinkageException} with the given message.
     *
     * @param message description of the linkage failure; should identify the generated proxy class
     */
    public WorkflowClientProxyLinkageException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code WorkflowClientProxyLinkageException} with the given message and cause.
     *
     * @param message description of the linkage failure; should identify the generated proxy class
     * @param cause the underlying reflective or linkage error that triggered this failure
     */
    public WorkflowClientProxyLinkageException(String message, Throwable cause) {
        super(message, cause);
    }
}
