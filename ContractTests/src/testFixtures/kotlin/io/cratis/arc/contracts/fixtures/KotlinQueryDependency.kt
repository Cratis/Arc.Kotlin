// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable

/** Dependency used by generated Kotlin query performer contracts. */
public class KotlinQueryDependency {
    public var invocationCount: Int = 0
        private set

    public fun models(prefix: String): List<KotlinQueryReadModel> {
        invocationCount++
        return listOf(KotlinQueryReadModel("$prefix-one"), KotlinQueryReadModel("$prefix-two"))
    }

    public fun page(value: String, pageable: Pageable, totalElements: Long): Page<KotlinQueryReadModel> {
        invocationCount++
        return PageImpl(listOf(KotlinQueryReadModel(value)), pageable, totalElements)
    }
}
