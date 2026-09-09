// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.cratis.arc.json.ArcPropertyNamingStrategy
import org.springframework.beans.factory.config.BeanPostProcessor

/** Applies Arc's Jackson 2 wire defaults without depending on Spring Boot's deprecated Jackson 2 customizer. */
internal class ArcJackson2ObjectMapperBeanPostProcessor : BeanPostProcessor {
    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (bean !is ObjectMapper) return bean

        bean.propertyNamingStrategy = ArcPropertyNamingStrategy()
        bean.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
        bean.enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature())
        bean.enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS.mappedFeature())
        bean.disable(
            SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
            SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS
        )
        return bean
    }
}
