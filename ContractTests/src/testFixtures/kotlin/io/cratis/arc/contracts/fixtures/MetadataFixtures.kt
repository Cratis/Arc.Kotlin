// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.concepts.ArcEnum
import io.cratis.arc.concepts.ArcEnumValue
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.concepts.Flags
import io.cratis.arc.polymorphism.DerivedType
import io.cratis.arc.validation.CreditCard
import io.cratis.arc.validation.Phone
import io.cratis.arc.validation.Url
import io.cratis.chronicle.events.EventType
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Negative
import jakarta.validation.constraints.NegativeOrZero
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Enum fixture reachable from generated metadata. */
public enum class FixtureState {
    New,
    Active
}

/** Arc enum whose integer constructor literals are preserved as explicit wire values. */
public enum class ExplicitFixtureState(private val wireValue: Int) : ArcEnum {
    Unknown(0),
    Ready(17);

    override fun value(): Int = wireValue
}

/** Arc enum that requires entry annotations because its constructor arguments are expressions. */
public enum class AnnotatedFixtureState(private val wireValue: Int) : ArcEnum {
    @ArcEnumValue(23)
    Computed(20 + 3);

    override fun value(): Int = wireValue
}

/** Flags fixture used to verify all<Name> metadata semantics. */
@Flags
public enum class FixturePermissions(private val wireValue: Int) : ArcEnum {
    None(0),
    Read(1),
    Write(2),
    Execute(4);

    override fun value(): Int = wireValue
}

/** Interface fixture rendered with the Interface template contract. */
public interface FixtureShape {
    public val name: String
}

/** Base class fixture used to preserve Type template inheritance. */
public abstract class FixtureShapeBase(public val createdAt: Instant)

/** First concrete interface derivative. */
@DerivedType("circle")
public data class FixtureCircle(
    override val name: String,
    public val radius: Double,
    public val created: Instant
) : FixtureShapeBase(created), FixtureShape

/** Second concrete interface derivative. */
@DerivedType("rectangle")
public data class FixtureRectangle(
    override val name: String,
    public val width: Double,
    public val height: Double,
    public val created: Instant
) : FixtureShapeBase(created), FixtureShape

/** Generic concept base whose validation is inherited by concrete string concepts. */
public abstract class RequiredTextConcept<T : CharSequence>(
    @field:NotBlank(message = "Concept value is required") private val conceptValue: T
) : ConceptAs<T> {
    final override fun value(): T = conceptValue
}

/** Generic concept base whose validation is inherited by concrete numeric concepts. */
public abstract class PositiveNumberConcept<T : Number>(
    @field:Positive(message = "Quantity must be positive") private val conceptValue: T
) : ConceptAs<T> {
    final override fun value(): T = conceptValue
}

/** String concept whose own and inherited Jakarta constraints become client validation rules. */
public data class CustomerName(
    @field:Size(min = 2, max = 40, message = "Customer name length is invalid") private val rawValue: String
) : RequiredTextConcept<String>(rawValue)

/** Numeric concept used to verify primitive proxy constructors and inherited validation. */
public data class Quantity(private val rawValue: Int) : PositiveNumberConcept<Int>(rawValue)

/** UUID concept used by Kotlin command metadata. */
public data class OrderId(private val rawValue: UUID) : ConceptAs<UUID> {
    override fun value(): UUID = rawValue
}

/** Date concept used by Kotlin command metadata. */
public data class DeliveryDate(private val rawValue: LocalDate) : ConceptAs<LocalDate> {
    override fun value(): LocalDate = rawValue
}

/** Time concept used by Kotlin command metadata. */
public data class DeliveryTime(private val rawValue: LocalTime) : ConceptAs<LocalTime> {
    override fun value(): LocalTime = rawValue
}

/** Enum concept used by Kotlin command metadata. */
public data class StateCode(private val rawValue: FixtureState) : ConceptAs<FixtureState> {
    override fun value(): FixtureState = rawValue
}

/** Query argument fixture reachable from generated metadata. */
public data class FixtureFilter(
    public val ids: List<UUID>,
    public val state: FixtureState?
)

/** Cyclic DTO fixture used to verify visited-set model collection. */
public data class CyclicFixture(
    public val name: String,
    public val next: CyclicFixture?
)

/** Plain command response DTO fixture. */
public data class FixtureResponse(
    public val identifier: UUID,
    public val state: FixtureState,
    public val labels: List<String>,
    public val optionalLabel: String?,
    public val cycle: CyclicFixture?,
    public val explicitState: ExplicitFixtureState,
    public val annotatedState: AnnotatedFixtureState,
    public val permissions: FixturePermissions,
    public val shape: FixtureShape,
    public val shapes: List<FixtureShape>,
    public val javaState: JavaFixtureState,
    public val javaAnnotatedState: JavaAnnotatedFixtureState,
    public val javaPermissions: JavaFixturePermissions,
    public val javaContract: JavaFixtureContract
)

/** Nested DTO fixture reached through Jakarta recursive-validation metadata. */
public data class ValidatedContact(
    @field:NotBlank(message = "Contact name is required") public val name: String,
    @param:Email(message = "Contact email is invalid") public val email: String
)

/** Command fixture with list metadata and a client-visible enumerable response. */
@Command
public data class MetadataCommand(
    @CommandKey @field:NotNull(message = "Command id is required") public val commandId: UUID,
    @get:Size(min = 1, max = 3, message = "Choose between one and three states")
    public val states: List<FixtureState>,
    @field:NotBlank(message = "Display name is required")
    @get:Size(min = 2, max = 40, message = "Display name length is invalid")
    public val displayName: String,
    @param:NotEmpty(message = "At least one label is required")
    public val labels: List<String>,
    @field:Pattern(regexp = "^[A-Z][A-Za-z ]+$", message = "Display name must start with an uppercase letter")
    public val formattedName: String,
    @field:Email(regexp = "^.+@example\\.com$", message = "Use an example.com address")
    public val email: String,
    @field:Phone(message = "Phone number is invalid") public val phone: String,
    @get:Url(message = "Website URL is invalid") public val website: String,
    @param:CreditCard(message = "Credit card number is invalid") public val creditCard: String,
    @field:Min(value = 18, message = "Age must be at least 18")
    @get:Max(value = 120, message = "Age must be at most 120")
    public val age: Int,
    @field:DecimalMin(value = "1.5", inclusive = false, message = "Ratio must exceed 1.5")
    @get:DecimalMax(value = "9.5", inclusive = true, message = "Ratio must not exceed 9.5")
    public val ratio: Double,
    @field:Positive(message = "Positive count is required") public val positive: Int,
    @field:PositiveOrZero(message = "Non-negative count is required") public val positiveOrZero: Int,
    @field:Negative(message = "Negative count is required") public val negative: Int,
    @field:NegativeOrZero(message = "Non-positive count is required") public val negativeOrZero: Int,
    @field:Valid public val contact: ValidatedContact,
    @field:Valid public val contacts: List<ValidatedContact>,
    @field:Valid @field:NotNull(message = "Customer name is required") public val customerName: CustomerName,
    @field:Valid public val optionalCustomerName: CustomerName?,
    @field:Valid @field:Size(min = 1, message = "At least one customer name is required")
    public val customerNames: List<CustomerName>,
    @field:Valid public val quantity: Quantity,
    public val orderId: OrderId,
    public val deliveryDate: DeliveryDate,
    public val deliveryTime: DeliveryTime,
    public val stateCode: StateCode,
    @field:Valid public val javaCode: JavaCustomerCode,
    public val javaQuantity: JavaQuantity,
    public val javaOrderId: JavaOrderId,
    public val javaDeliveryDate: JavaDeliveryDate,
    public val javaDeliveryTime: JavaDeliveryTime,
    public val javaStateCode: JavaStateCode
) {
    public fun handle(): List<FixtureResponse> = emptyList()
}

/** Chronicle-like event fixture that is consumed by the command pipeline. */
@EventType
public data class MetadataEvent(public val value: String)

/** Command whose event return must not be exposed as a client response. */
@Command
public data class EventCommand(@CommandKey public val commandId: String) {
    public fun handle(): MetadataEvent = MetadataEvent(commandId)
}
