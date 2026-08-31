// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import io.vertx.core.json.jackson.DatabindCodec;

/** Named JSON profile that exposes structured weather values in snake case. */
public final class ExampleJsonProfile {
    public static final String ID = "weather-example";

    private ExampleJsonProfile() {}

    /** Creates the profile contribution used by the weather tool and MCP boundary. */
    public static JsonMapperProfile create() {
        var mapper = DatabindCodec.mapper().copy();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return JsonMapperProfiles.of(JsonProfileId.of(ID), mapper);
    }
}
