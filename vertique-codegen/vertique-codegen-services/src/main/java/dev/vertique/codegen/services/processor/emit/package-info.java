// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Code emitters for {@code ServiceContractProcessor}.
 *
 * <p>{@link dev.vertique.codegen.services.processor.emit.ContributorEmitter} writes one
 * {@code {Contract}_ContractContributor} source file per validated contract model.
 *
 * <p>{@link dev.vertique.codegen.services.processor.emit.ContributorModuleEmitter} accumulates
 * models during the round and writes a single {@code GeneratedServicesModule}
 * at the end.
 */
package dev.vertique.codegen.services.processor.emit;
