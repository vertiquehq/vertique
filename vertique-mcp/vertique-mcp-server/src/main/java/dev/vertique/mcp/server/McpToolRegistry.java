// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The one immutable, global-name-ordered tool registry composed once per Vert.x context.
 *
 * <p>{@link #build(Set)} is the sole construction path: it asks every explicitly contributed {@link
 * McpToolInvoker} for its descriptor exactly once, fails startup before any route mounts when two
 * contributions publish the same tool name, and compiles the owned {@link McpSchemaRegistry} from the
 * resulting descriptor set — failing the same way when a descriptor's schema is unsupported or
 * uncompilable. Nothing here is discovered by classpath scanning; only explicitly contributed
 * invokers are registered.
 *
 * <p>The returned registry is backed by an unmodifiable, name-ordered map — insertion order of the
 * contributed set never affects the published order — and exposes a stable digest computed from the
 * exact tool name and schema content of every entry in that same global name order. Two independent
 * builds of the same composition therefore produce the same digest; the digest changes only when the
 * registered tool set or a published schema changes. A later slice's cursor codec binds this digest to
 * invalidate a stale cursor across deployments.
 *
 * <p>Not part of the application-facing public surface: package-private per the frozen artifact
 * inventory ({@code contracts/type-and-module-inventory.md} § 7), exactly like its owned {@link
 * McpSchemaRegistry}. Its owning proofs ({@code McpSchemaStartupTest},
 * {@code McpOptionalCapabilityStartupTest}) live in this same package for exactly that reason —
 * package-private access requires it, matching {@code McpValidatorConcurrencyTest}'s established T009
 * precedent.
 */
final class McpToolRegistry {

    private final Map<String, McpToolInvoker> invokersByName;
    private final Map<String, McpToolDescriptor> descriptorsByName;
    private final McpSchemaRegistry schemaRegistry;
    private final String digest;

    private McpToolRegistry(
            Map<String, McpToolInvoker> invokersByName,
            Map<String, McpToolDescriptor> descriptorsByName,
            McpSchemaRegistry schemaRegistry,
            String digest) {
        this.invokersByName = invokersByName;
        this.descriptorsByName = descriptorsByName;
        this.schemaRegistry = schemaRegistry;
        this.digest = digest;
    }

    /**
     * Builds the one immutable registry from the explicitly contributed invoker set.
     *
     * <p>Every contribution is asked for its descriptor exactly once, and the result is keyed by tool
     * name in global name order, so the published order never depends on contribution order. The
     * owned {@link McpSchemaRegistry} is compiled from the resulting descriptor set before this method
     * returns, so an unsupported or uncompilable schema fails here too, before any route mounts.
     *
     * @param contributedInvokers the generated {@code @IntoSet} invoker contributions
     * @return the immutable registry, keyed by tool name in global name order
     * @throws ConfigurationException if two contributions publish the same tool name, or if a
     *     descriptor's schema is unsupported or cannot be compiled; no registry is produced either way
     */
    static McpToolRegistry build(Set<McpToolInvoker> contributedInvokers) {
        Objects.requireNonNull(contributedInvokers, "contributedInvokers");
        Map<String, McpToolInvoker> invokers = new TreeMap<>();
        Map<String, McpToolDescriptor> descriptors = new TreeMap<>();
        for (McpToolInvoker invoker : contributedInvokers) {
            McpToolDescriptor descriptor = invoker.descriptor();
            String name = descriptor.name();
            if (invokers.putIfAbsent(name, invoker) != null) {
                throw new ConfigurationException(
                        "Duplicate MCP tool name '" + name + "': two generated invokers publish it");
            }
            descriptors.put(name, descriptor);
        }
        Map<String, McpToolInvoker> immutableInvokers = Collections.unmodifiableMap(invokers);
        Map<String, McpToolDescriptor> immutableDescriptors = Collections.unmodifiableMap(descriptors);
        McpSchemaRegistry schemaRegistry;
        try {
            schemaRegistry = new McpSchemaRegistry(immutableDescriptors);
        } catch (RuntimeException uncompilable) {
            throw new ConfigurationException(
                    "Unsupported MCP tool schema or mapping contract: " + boundedMessage(uncompilable), uncompilable);
        }
        return new McpToolRegistry(
                immutableInvokers, immutableDescriptors, schemaRegistry, digestOf(immutableDescriptors));
    }

    /**
     * Returns the immutable, global-name-ordered invoker registry.
     *
     * @return the registry, keyed by tool name in global name order; unmodifiable
     */
    Map<String, McpToolInvoker> invokersByName() {
        return invokersByName;
    }

    /**
     * Returns the immutable, global-name-ordered descriptor registry.
     *
     * @return the registry, keyed by tool name in global name order; unmodifiable
     */
    Map<String, McpToolDescriptor> descriptorsByName() {
        return descriptorsByName;
    }

    /**
     * Returns the compiled validator set owned by this registry.
     *
     * @return the schema registry compiled once from this registry's descriptor set
     */
    McpSchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    /**
     * Returns the stable digest of this registry's exact tool set and schema content, in global name
     * order.
     *
     * @return the digest; identical across two independent builds of the same composition
     */
    String digest() {
        return digest;
    }

    /**
     * Computes the stable digest of a descriptor registry: a SHA-256 hash over each entry's tool name
     * and schema content, walked in the map's iteration order (global name order for a {@link
     * TreeMap}-backed registry).
     *
     * @param descriptorsByName the built, name-ordered descriptor registry
     * @return the lower-case hex-encoded digest
     */
    private static String digestOf(Map<String, McpToolDescriptor> descriptorsByName) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, McpToolDescriptor> entry : descriptorsByName.entrySet()) {
                McpToolDescriptor descriptor = entry.getValue();
                updateWithBoundary(sha256, entry.getKey());
                updateWithBoundary(sha256, descriptor.inputSchema());
                updateWithBoundary(sha256, descriptor.outputSchema() == null ? "" : descriptor.outputSchema());
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }

    /** Feeds one UTF-8 value followed by a NUL boundary byte, so adjacent field values cannot collide. */
    private static void updateWithBoundary(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    /** Bounds an uncontrolled failure message so a wrapped cause cannot make the outer message unbounded. */
    private static String boundedMessage(RuntimeException cause) {
        String message = cause.getMessage();
        if (message == null) {
            return cause.getClass().getSimpleName();
        }
        return message.length() > 256 ? message.substring(0, 256) : message;
    }
}
