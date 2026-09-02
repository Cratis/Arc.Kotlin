// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.databind.PropertyNamingStrategies

/** Jackson naming strategy backed by [ArcCamelCase]. */
public class ArcPropertyNamingStrategy : PropertyNamingStrategies.NamingBase() {
    override fun translate(propertyName: String?): String? = ArcCamelCase.convert(propertyName)
}
