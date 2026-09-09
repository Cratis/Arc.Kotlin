// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.cratis.arc.json.ArcPropertyNamingStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/** Internal static bean wiring for Arc's Jackson mapper defaults. */
@Configuration(proxyBeanMethods = false)
final class ArcJacksonObjectMapperConfiguration {
    private ArcJacksonObjectMapperConfiguration() {
    }

    @Bean("arcJacksonCustomizer")
    @ConditionalOnMissingBean(name = "arcJacksonCustomizer")
    static JsonMapperBuilderCustomizer arcJacksonCustomizer() {
        return builder -> builder
            .propertyNamingStrategy(new ArcPropertyNamingStrategy())
            .changeDefaultPropertyInclusion(inclusion -> inclusion
                .withValueInclusion(JsonInclude.Include.NON_NULL)
                .withContentInclusion(JsonInclude.Include.NON_NULL))
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    }
}
