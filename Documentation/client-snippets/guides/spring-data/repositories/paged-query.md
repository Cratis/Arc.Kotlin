```kotlin
@JvmStatic
fun all(
    pageable: Pageable,
    sort: Sort,
    @FromServices tasks: TaskViewRepository
): Page<TaskView> {
    check(pageable.sort == sort) { "Paging and sorting must agree." }
    return tasks.findAll(pageable)
}
```
