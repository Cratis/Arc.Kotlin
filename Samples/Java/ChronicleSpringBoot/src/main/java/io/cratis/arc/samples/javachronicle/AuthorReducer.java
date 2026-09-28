// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.chronicle.events.EventContext;
import io.cratis.chronicle.observation.Reducer;

@Reducer
public final class AuthorReducer {
    public Author registered(AuthorRegistered event, Author state, EventContext context) {
        return new Author(context.getEventSourceId(), event.name());
    }
}
