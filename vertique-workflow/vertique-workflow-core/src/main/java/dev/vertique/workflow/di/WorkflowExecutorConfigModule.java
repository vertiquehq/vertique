// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.di;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/** Opt-in parsed workflow.executor configuration; requires the canonical ConfigParser binding. */
@Module
public abstract class WorkflowExecutorConfigModule {
    private WorkflowExecutorConfigModule() {}

    @Provides
    @Singleton
    static WorkflowExecutorConfig executorConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(config, "workflow", "executor"), WorkflowExecutorConfig.class);
    }
}
