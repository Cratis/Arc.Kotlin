```kotlin
suspend fun createsATask(module: ArcArtifactModule, repository: TaskRepository) {
    val result = CommandScenario(module, CreateTask::class.java)
        .addService(TaskRepository::class.java, repository)
        .execute(CreateTask("Try Arc"))

    result.shouldSucceed().shouldHaveResponse(TaskCreated::class.java)
}
```
