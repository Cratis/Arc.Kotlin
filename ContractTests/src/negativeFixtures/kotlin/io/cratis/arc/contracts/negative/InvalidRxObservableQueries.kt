// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.ReadModel

/** Instance method (not in companion) returning Observable — must be in the companion object. */
@ReadModel
public data class KotlinRxInstanceQuery(public val value: String) {
    public fun observe(): io.reactivex.rxjava3.core.Observable<KotlinRxInstanceQuery> = error("not called")
}

/** Instance method (not in companion) returning Subject — must be in the companion object. */
@ReadModel
public data class KotlinRxSubjectInstanceQuery(public val value: String) {
    public fun observe(): io.reactivex.rxjava3.subjects.Subject<KotlinRxSubjectInstanceQuery> = error("not called")
}
