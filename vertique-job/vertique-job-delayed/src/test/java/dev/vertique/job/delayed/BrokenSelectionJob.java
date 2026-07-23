// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

/** Contract fixture whose generated proxy companion throws on construction (loud-failure path). */
@DelayedJobContract(name = "broken-selection")
interface BrokenSelectionJob extends DelayedJobClient<String> {}
