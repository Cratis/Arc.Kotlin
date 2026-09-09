// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Internal static bean wiring for Arc's Jackson 2 mapper defaults. */
@Configuration(proxyBeanMethods = false)
final class ArcJackson2ObjectMapperConfiguration {
    private ArcJackson2ObjectMapperConfiguration() {
    }

    @Bean
    @ConditionalOnMissingBean(name = "arcJacksonCustomizer")
    static BeanPostProcessor arcJacksonCustomizer() {
        return new ArcJackson2ObjectMapperBeanPostProcessor();
    }
}
