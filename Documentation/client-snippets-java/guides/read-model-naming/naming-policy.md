```java
interface NamingPolicy {
    String getReadModelName(Class<?> readModelType);

    default String getPropertyName(String name) {
        return name;
    }
}
```
