// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.queries.QueryHttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl

@ReadModel
public data class ObservableSpringPageReadModel(public val value: String) {
    public companion object {
        public fun invalid(): Flow<Page<ObservableSpringPageReadModel>> =
            flowOf(PageImpl(listOf(ObservableSpringPageReadModel("invalid"))))
    }
}

@ReadModel
public data class NullableSpringPageReadModel(public val value: String) {
    public companion object {
        public fun invalid(): Page<NullableSpringPageReadModel>? = null
    }
}

@ReadModel
public data class WrongSpringPageElementReadModel(public val value: String) {
    public companion object {
        @QueryHttpMethod
        public fun invalid(): Page<String> = PageImpl(listOf("invalid"))
    }
}

public class UnsupportedSpringPageSubtype : PageImpl<SpringPageSubtypeReadModel>(
    listOf(SpringPageSubtypeReadModel("invalid"))
)

@ReadModel
public data class SpringPageSubtypeReadModel(public val value: String) {
    public companion object {
        @QueryHttpMethod
        public fun invalid(): UnsupportedSpringPageSubtype = UnsupportedSpringPageSubtype()
    }
}
