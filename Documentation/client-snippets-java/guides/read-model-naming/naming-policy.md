```java
interface NamingPolicy {
    String getReadModelName(Class<?> readModelType);
    String getPropertyName(String name);  // default: returns name unchanged
}
```
