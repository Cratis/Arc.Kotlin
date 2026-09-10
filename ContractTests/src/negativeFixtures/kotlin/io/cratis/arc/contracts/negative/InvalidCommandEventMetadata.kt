// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandEventStreamId
import io.cratis.arc.artifacts.CommandEventStreamIdProvider
import io.cratis.arc.artifacts.CommandEventSubject
import io.cratis.arc.artifacts.CommandEventSubjectProvider

@Command
@CommandEventStreamId("   ")
public class BlankCommandEventStreamId {
    public fun handle(): Unit = Unit
}

@Command
@CommandEventSubject("unsafe\nsubject")
public class ControlCharacterCommandEventSubject {
    public fun handle(): Unit = Unit
}

@Command
@CommandEventStreamId("static-stream")
public class AmbiguousCommandEventStreamId : CommandEventStreamIdProvider {
    override fun eventStreamId(): String = "dynamic-stream"
    public fun handle(): Unit = Unit
}

@Command
@CommandEventSubject("static-subject")
public class AmbiguousCommandEventSubject : CommandEventSubjectProvider {
    override fun eventSubject(): String = "dynamic-subject"
    public fun handle(): Unit = Unit
}
