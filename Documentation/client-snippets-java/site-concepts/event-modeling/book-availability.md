```java
import io.cratis.chronicle.events.EventType;
import io.cratis.chronicle.projections.Decrement;
import io.cratis.chronicle.projections.FromEvent;
import io.cratis.chronicle.projections.SetFrom;
import io.cratis.chronicle.readModels.ReadModel;

@EventType
public record BookAdded(String title, int copies) { }

@ReadModel
@FromEvent(eventType = BookAdded.class)
@FromEvent(eventType = BookReserved.class)
public class BookAvailability {
    public String id = "";
    public String title = "";

    @SetFrom(propertyPath = "copies", eventType = BookAdded.class)
    @Decrement(eventType = BookReserved.class)
    public int availableCopies;
}
```
