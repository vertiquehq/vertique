// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.workflow;

import dagger.BindsOptionalOf;
import dagger.Module;
import dev.vertique.micrometer.MetricsConfig;

/**
 * Dagger module that contributes Micrometer workflow metrics through the workflow executor observer SPI.
 *
 * <p>Install this module alongside the workflow core module and
 * {@link dev.vertique.micrometer.MicrometerModule} in the application's Dagger component.
 * The optional metrics configuration defaults to enabled when the core Micrometer module is not
 * installed.
 */
@Module(includes = GeneratedRegistrationsModule.class)
public abstract class MicrometerWorkflowModule {

    private MicrometerWorkflowModule() {}

    /**
     * Declares {@link MetricsConfig} as an optional binding for the workflow observer.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();
}
