// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * INTERNAL framework seam — not an application contract and outside the maturity promise.
 *
 * <p>Framework-internal SPIs the engine requires a database adapter to implement. Every type in this package is
 * internal to the engine module: applications do not implement or call them, and they may change without a
 * deprecation cycle.
 */
package dev.vertique.workflow.engine.spi;
