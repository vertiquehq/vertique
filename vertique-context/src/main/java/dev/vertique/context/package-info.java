// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Context-propagation substrate runtime.
 *
 * <p>This package hosts the runtime implementations that back the substrate SPIs declared in
 * {@code dev.vertique.core.context}: the {@link dev.vertique.core.context.ContextHolder} default
 * implementation ({@link dev.vertique.context.DefaultContextHolder}), encoder/decoder registries,
 * the {@link dev.vertique.context.DurableContextPropagator} orchestrator, the inbound lifecycle
 * helper ({@link dev.vertique.context.InboundExecutionContextScope}), and the
 * {@link dev.vertique.context.ContextRuntimeModule} Dagger module.
 *
 * <p>Feature modules (e.g. {@code vertique-logging}, {@code vertique-correlation}) contribute
 * SPI implementations via {@code @IntoSet} multibindings. This package does not import any
 * feature module — it only depends on contracts in {@code dev.vertique.core.context}.
 *
 * <p>For convenience helpers that wrap the substrate registration patterns see
 * {@link dev.vertique.context.ServiceDispatchCodecs} and
 * {@link dev.vertique.context.DurableJsonContextCodecs}.
 */
package dev.vertique.context;
