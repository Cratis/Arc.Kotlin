// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;
import io.reactivex.rxjava3.core.Observable;

/** Non-static method returning Observable — must be static. */
@ReadModel
public record NonStaticJavaRxQuery(String value) {
    public Observable<NonStaticJavaRxQuery> observe() {
        return Observable.just(this);
    }
}
