// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JavaPoet-based utilities for generating reflection-free method/parameter metadata sources.
 *
 * <p>Contains {@link dev.vertique.codegen.meta.MetadataEmitter}, which emits a
 * {@code dev.vertique.core.codegen.MethodMetadata} implementation whose accessors return
 * compile-time constants (method name, declaring/return/parameter {@code Class} literals, and an
 * ordered {@code ParameterMetadata} list) so downstream consumers obtain method metadata without
 * reflecting at call time.
 */
package dev.vertique.codegen.meta;
