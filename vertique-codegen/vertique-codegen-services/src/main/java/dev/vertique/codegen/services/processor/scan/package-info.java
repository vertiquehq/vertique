// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * APT-layer scanning and extraction components for {@code ServiceContractProcessor}.
 *
 * <p>Components in this package walk the APT element hierarchy to discover
 * {@code @ServiceContract} implementations, resolve their contract types, and build
 * {@link dev.vertique.codegen.services.processor.scan.ContractModel} records ready for
 * validation and emission.
 */
package dev.vertique.codegen.services.processor.scan;
