// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextValue;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only ContextValue fixtures used by vertique-context holder/codec tests. */
record StringCtx(String value) implements ContextValue {}

/** Test-only ContextValue fixture for integer context values. */
record IntCtx(int value) implements ContextValue {}

/** Test-only ContextValue fixture for mutable counter context values. */
record Counter(AtomicInteger counter) implements ContextValue {}
