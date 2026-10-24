// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import io.cratis.fundamentals.strings.toCamelCase

import tools.jackson.databind.PropertyNamingStrategies

/** Jackson naming strategy backed by Fundamentals' [toCamelCase]. */
public class ArcPropertyNamingStrategy : PropertyNamingStrategies.NamingBase() {
    override fun translate(propertyName: String?): String? = propertyName?.toCamelCase()
}
