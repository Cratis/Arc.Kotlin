// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.Command
import io.cratis.arc.commands.ArcOneOf
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import java.util.UUID

public data class NullableTopLevelClient(public val value: String)

@Command
public class NullableTopLevelClientCommand {
    public fun handle(): NullableTopLevelClient? = null
}

public data class NullablePairMember(public val value: String)

@Command
public class NullablePairMemberCommand {
    public fun handle(): Pair<NullablePairMember?, ValidationResult> =
        Pair(null, ValidationResult(ValidationResultSeverity.Error, "invalid"))
}

public data class NullableTripleMember(public val value: String)

@Command
public class NullableTripleMemberCommand {
    public fun handle(): Triple<ValidationResult, NullableTripleMember?, ValidationResult> = Triple(
        ValidationResult(ValidationResultSeverity.Error, "first"),
        null,
        ValidationResult(ValidationResultSeverity.Error, "third")
    )
}

public data class NullableArcOneOfShapeMember(public val value: String)

@Command
public class NullableArcOneOfShapeCommand {
    public fun handle(): ArcOneOf<Pair<NullableArcOneOfShapeMember?, ValidationResult>> = ArcOneOf.of(
        Pair(null, ValidationResult(ValidationResultSeverity.Error, "invalid"))
    )
}

public data class NullableArcOneOfMember(public val value: String)

@Command
public class NullableArcOneOfMemberCommand {
    public fun handle(): Pair<ArcOneOf<NullableArcOneOfMember>?, ValidationResult> =
        Pair(null, ValidationResult(ValidationResultSeverity.Error, "invalid"))
}

public data class NullableCommandResultPayload(public val value: String)

@Command
public class NullableCommandResultPayloadCommand {
    public fun handle(): Pair<CommandResult<NullableCommandResultPayload?>, ValidationResult> = Pair(
        CommandResult.success<NullableCommandResultPayload?>(UUID.randomUUID(), null),
        ValidationResult(ValidationResultSeverity.Error, "invalid")
    )
}

public data class NullableCommandResultMember(public val value: String)

@Command
public class NullableCommandResultMemberCommand {
    public fun handle(): Pair<CommandResult<NullableCommandResultMember>?, ValidationResult> =
        Pair(null, ValidationResult(ValidationResultSeverity.Error, "invalid"))
}

public data class NullableClientCollectionElement(public val value: String)

@Command
public class NullableClientCollectionCommand {
    public fun handle(): List<NullableClientCollectionElement?> =
        listOf<NullableClientCollectionElement?>(null)
}

@Command
public class NullableHandledCollectionCommand {
    public fun handle(): List<ValidationResult?> = listOf<ValidationResult?>(null)
}

public data class NullableClientArrayElement(public val value: String)

@Command
public class NullableClientArrayCommand {
    public fun handle(): Array<NullableClientArrayElement?> = arrayOf<NullableClientArrayElement?>(null)
}
