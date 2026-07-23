// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * APT-side scanning for {@code @DelayedJobContract} interfaces: resolves the contract's payload type
 * parameter {@code P} from {@code DelayedJobClient<P>} and captures the contract metadata into a model.
 */
package dev.vertique.codegen.delayed.processor.scan;
