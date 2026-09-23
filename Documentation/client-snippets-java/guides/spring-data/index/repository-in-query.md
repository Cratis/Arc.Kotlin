```java
@Entity
@ReadModel
public class TaskView {
    @Id
    public String id;
    public String title;

    public static List<TaskView> all(@FromServices TaskViewRepository tasks) {
        return tasks.findAll();
    }
}

interface TaskViewRepository extends JpaRepository<TaskView, String> { }
```
