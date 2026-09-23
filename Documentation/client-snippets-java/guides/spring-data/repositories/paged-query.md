```java
public static Page<TaskView> all(
    Pageable pageable,
    Sort sort,
    @FromServices TaskViewRepository tasks
) {
    if (!pageable.getSort().equals(sort)) {
        throw new IllegalStateException("Paging and sorting must agree.");
    }
    return tasks.findAll(pageable);
}
```
