// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

/**
 * Generated-runtime metadata for one declared tool parameter.
 *
 * <p>The annotation processor emits one instance per declared parameter, in declaration order. The
 * carrier component name is the collision-safe generated record component ({@code argument0},
 * {@code argument1}, ...); the external name is the protocol-visible property name, which may be a
 * Java keyword or otherwise not a legal identifier. Application code neither builds nor consumes
 * this type.
 *
 * @param carrierComponentName the generated input-carrier record component name
 * @param externalName the protocol-visible argument name
 * @param description the parameter description published in the input schema
 */
public record McpToolParameterMetadata(String carrierComponentName, String externalName, String description) {}
