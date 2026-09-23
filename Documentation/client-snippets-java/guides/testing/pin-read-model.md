```java
static void renamesATask(ArcArtifactModule module) throws Exception {
    CommandScenario<RenameTask> configured = new CommandScenario<>(module, RenameTask.class)
        .withReadModelForKey(TaskView.class, "task-1", new TaskView("task-1", "Current title", 7L));

    try (BlockingCommandScenario<RenameTask> scenario = new BlockingCommandScenario<>(configured)) {
        scenario.execute(new RenameTask("task-1", "Renamed title", 7L));
    }
}
```
