---
title: Read-model naming policy
description: Control how Arc derives MongoDB collection names from read-model types, and override the default for types where the kernel and the JVM inflector disagree.
---

## How Arc names read-model collections

When Arc resolves a MongoDB read-model collection, it delegates to a `NamingPolicy` bean to convert the JVM type into a storage name. The `NamingPolicy` interface declares two methods:

- `getReadModelName(Class<?>)` — returns the collection name for a read-model type. Must be non-blank and stable across calls for the same type.
- `getPropertyName(String)` — returns the stored field name for a declared member name. The default implementation returns the name unchanged.

```kotlin
interface NamingPolicy {
    fun getReadModelName(readModelType: Class<*>): String
    fun getPropertyName(name: String): String  // default: returns name unchanged
}
```

## Default policy

`DefaultNamingPolicy` is registered automatically by the MongoDB integration. It pluralises the simple class name using [Evo Inflector](https://github.com/atteo/evo-inflector) (`org.atteo:evo-inflector`).

```kotlin
// Kotlin — uses default (pluralizeReadModelNames = true)
class DefaultNamingPolicy(pluralizeReadModelNames: Boolean = true) : NamingPolicy
```

```java
// Java
NamingPolicy policy = new DefaultNamingPolicy();           // pluralises
NamingPolicy flat    = new DefaultNamingPolicy(false);     // returns simple name as-is
```

Setting `pluralizeReadModelNames = false` returns the simple class name unchanged.

## The Chronicle Kernel is authoritative

Pluralisation on the JVM uses Evo Inflector, a different library from the Humanizer library used by the .NET Chronicle Kernel. The two libraries agree on most common English nouns, but where they disagree on a specific noun the kernel is authoritative: the application must override the naming policy for that type so that the JVM reads from the collection the kernel writes to.

The cases pinned by `DefaultNamingPolicyTests` record what Evo Inflector actually produces. A library upgrade that changes a plural will fail those tests rather than silently moving a collection name.

## Override the policy for a specific type

Register a Spring bean that implements `NamingPolicy` and is annotated with `@ConditionalOnMissingBean` in your autoconfiguration, or supply it directly:

```kotlin
@Configuration
class NamingConfig {
    @Bean
    fun namingPolicy(): NamingPolicy = object : NamingPolicy {
        override fun getReadModelName(readModelType: Class<*>): String =
            when (readModelType) {
                // The kernel writes to "PersonProjections"; the JVM inflector would produce "People".
                PersonView::class.java -> "PersonProjections"
                else -> DefaultNamingPolicy().getReadModelName(readModelType)
            }
    }
}
```

```java
// Java equivalent
@Configuration
class NamingConfig {
    @Bean
    NamingPolicy namingPolicy() {
        return readModelType -> {
            if (readModelType == PersonView.class) return "PersonProjections";
            return new DefaultNamingPolicy().getReadModelName(readModelType);
        };
    }
}
```

The `NamingPolicy` bean is consulted once per type at lookup time. Implementations are responsible for their own caching when the computation is expensive.
