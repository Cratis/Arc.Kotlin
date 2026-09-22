```kotlin
val conversions = MongoCustomConversions.create { adapter ->
    adapter.registerConverter(UuidWrite())
    adapter.registerConverter(UuidRead())
    adapter.registerConverter(TextWrite())
    adapter.registerConverter(TextRead())
    adapter.registerConverter(LongWrite())
    adapter.registerConverter(LongRead())
}
```
