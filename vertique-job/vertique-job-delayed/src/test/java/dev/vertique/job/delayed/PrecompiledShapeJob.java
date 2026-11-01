// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

/** Contract fixture whose companion proxy keeps the constructor shape of an already compiled generated proxy. */
@DelayedJobContract(name = "precompiled-shape", maxAttempts = 4, queue = "mail", priority = 7)
interface PrecompiledShapeJob extends DelayedJobClient<String> {}
