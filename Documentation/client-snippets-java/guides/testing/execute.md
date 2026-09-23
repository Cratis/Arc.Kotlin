```java
static void createsATask(ArcArtifactModule module, TaskRepository repository) throws Exception {
    CommandScenario<CreateTask> configured = new CommandScenario<>(module, CreateTask.class)
        .addService(TaskRepository.class, repository);

    try (BlockingCommandScenario<CreateTask> scenario = new BlockingCommandScenario<>(configured)) {
        TaskCreated response = scenario.execute(new CreateTask("Try Arc"))
            .shouldSucceed()
            .shouldHaveResponse(TaskCreated.class);
    }
}
```
