```kotlin
suspend fun renamesATask(module: ArcArtifactModule) {
    val result = CommandScenario(module, RenameTask::class.java)
        .withReadModelForKey(TaskView::class.java, "task-1", TaskView("task-1", "Current title", 7))
        .execute(RenameTask("task-1", "Renamed title", 7))
}
```
