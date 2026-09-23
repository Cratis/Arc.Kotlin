```java
static void rejectsAMissingTask(ArcArtifactModule module) throws Exception {
    CommandScenario<RenameTask> configured = new CommandScenario<>(module, RenameTask.class)
        .withReadModel(TaskView.class, null);

    try (BlockingCommandScenario<RenameTask> scenario = new BlockingCommandScenario<>(configured)) {
        scenario.execute(new RenameTask("missing", "Renamed title", 0L))
            .shouldHaveValidation(null, null, ValidationResultReasons.DEPENDENCY_UNAVAILABLE);
    }
}
```
