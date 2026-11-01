// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/** Correlated signal sources: the {@code SignalMessage} a source's stages read, the {@code SignalNoMatch} policy, the {@code SignalSourceRefusal} a {@code dispatchAs} function fails with to refuse a message, and the {@code SignalSourceObserver} that is told the outcome of each message as a {@code SignalSourceEvent}. {@code WorkflowSignalSource} marks a source class that generated wiring registers into the {@code Set} of the {@code SignalSourceType} interface it implements. {@code WorkflowSignalTarget} declares the signals of an interface method that a transport's processor turns into such a class, and {@code SignalSourceForm} marks an annotation that does the same for its interface. */
package dev.vertique.workflow.source;
