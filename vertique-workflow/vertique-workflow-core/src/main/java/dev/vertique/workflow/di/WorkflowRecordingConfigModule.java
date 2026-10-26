// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.di;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Opt-in parsed {@code workflow.recording} lock timeout and payload-size configuration; requires the
 * canonical {@link ConfigParser} binding. Defaults and bounds are validated by {@link WorkflowRecordingConfig}.
 */
@Module
public abstract class WorkflowRecordingConfigModule {
    private WorkflowRecordingConfigModule() {}

    @Provides
    @Singleton
    static WorkflowRecordingConfig recordingConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(config, "workflow", "recording"), WorkflowRecordingConfig.class);
    }
}
