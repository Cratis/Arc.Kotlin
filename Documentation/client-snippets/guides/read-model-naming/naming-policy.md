```kotlin
interface NamingPolicy {
    fun getReadModelName(readModelType: Class<*>): String
    fun getPropertyName(name: String): String  // default: returns name unchanged
}
```
