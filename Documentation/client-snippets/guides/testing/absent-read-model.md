```kotlin
suspend fun rejectsAMissingTask(module: ArcArtifactModule) {
    val result = CommandScenario(module, RenameTask::class.java)
        .withReadModel(TaskView::class.java, null)
        .execute(RenameTask("missing", "Renamed title", 0))
}
```
