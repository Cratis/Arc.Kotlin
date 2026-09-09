// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import org.springframework.web.context.request.async.DeferredResult;

/** Java boundary for exercising the runtime-null result that Spring 7's Kotlin annotations reject at compile time. */
public final class DeferredResults {
    private DeferredResults() {
    }

    public static boolean setResult(DeferredResult<Object> deferred, Object value) {
        return deferred.setResult(value);
    }
}
