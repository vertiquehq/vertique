// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.avro;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import jakarta.inject.Singleton;

/**
 * Dagger module that contributes the Apicurio-backed Avro {@link KafkaSerdeProvider} to the
 * {@code Set<KafkaSerdeProvider>} multibinding declared by {@code KafkaModule}.
 *
 * <p>Include this module in the application {@code @Component} (alongside {@code KafkaModule}) to enable
 * the {@code "avro"} value format. Without it the {@code "avro"} format is unavailable; the
 * application uses whatever value-format providers it includes (e.g. JSON via
 * {@code vertique-kafka-json} + {@code KafkaJsonModule}).
 */
@Module
public abstract class AvroModule {

    private AvroModule() {}

    /**
     * Provides the Apicurio Avro serde provider, contributed into the format-provider multibinding.
     *
     * @param kafkaConfig the typed Kafka config (its {@code schemaRegistry} block is read)
     * @return the Avro format provider
     */
    @Provides
    @Singleton
    @IntoSet
    static KafkaSerdeProvider avroSerdeProvider(KafkaConfig kafkaConfig) {
        return new ApicurioAvroSerdeProvider(kafkaConfig);
    }
}
