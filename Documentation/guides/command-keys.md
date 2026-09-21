---
title: Identify a command with a key
description: Declare which property identifies a command, so Arc can resolve the state it acts on and route its events.
---

Most commands act on something that already exists — a specific order, a specific
account. Arc needs to know which one, before your handler runs, so that it can
resolve the current state the command operates on and so that integrations can
route what the command produces.

You tell it by marking the identifying property with `@CommandKey`.

```kotlin
@Command
data class ChangeShippingAddress(
    @CommandKey val orderId: OrderId,
    val address: Address
) {
    suspend fun handle(@FromServices orders: OrderRepository) {
        orders.updateAddress(orderId, address)
    }
}
```

A command without a key is perfectly valid — a command that creates something has
nothing to identify yet.

## Exactly one key

`@CommandKey` may appear on **one** property. Two of them is
[`ARCKSP0106`](../reference/diagnostics.md), a compile error, because Arc would
otherwise have to guess which identity the command means and would guess
consistently wrongly for one of them.

The annotation is accepted on a property, a field, a value parameter or a
property getter, which covers Kotlin data classes, Kotlin classes with explicit
properties, and Java records alike.

## Computing a key instead of declaring one

When the key is not a single declared property — it is derived from two of them,
or comes from somewhere else on the command — implement `CommandKeyProvider`:

```kotlin
@Command
class ArchiveTenantData(val tenant: String, val year: Int) : CommandKeyProvider {
    override fun commandKey(): Any = "$tenant/$year"

    suspend fun handle(@FromServices archive: ArchiveService) {
        archive.run(tenant, year)
    }
}
```

`CommandKeyProvider` is a `fun interface` with a single `commandKey()` method
returning `Any?`, so a Java record or class implements it without ceremony. From
Kotlin you can also read it as a property: `command.key`.

Return `null` to say this command has no key, which is the same as not declaring
one.

## What the key is used for

The key identifies the command instance. It is not a route parameter and not a
validation concern — the value is not checked for existence, and declaring a key
does not by itself load anything.

Where it matters is in integrations that need to know *what* the command is
about. The Chronicle integration uses it to decide the event source a command's
events belong to; a command that returns events without a resolvable key is
rejected at that point rather than appending to the wrong stream.

## Relationship to the C# implementation

The C# implementation spells the same two mechanisms `[Key]` and
`ICanProvideKeyForCommand.GetKey()`. The concept, the "exactly one" rule and the
role in resolving command state are the same; only the names differ.
