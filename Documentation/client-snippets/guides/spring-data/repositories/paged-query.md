```kotlin
@JvmStatic
fun all(
    pageable: Pageable,
    sort: Sort,
    @FromServices tasks: TaskViewRepository
): Page<TaskView> {
    check(pageable.sort == sort)
    return tasks.findAll(pageable)
}
```
