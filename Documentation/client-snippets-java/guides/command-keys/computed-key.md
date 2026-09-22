```java
@Command
public record ArchiveTenantData(String tenant, int year) implements CommandKeyProvider {
    @Override
    public Object commandKey() {
        return tenant + "/" + year;
    }

    public void handle(@FromServices ArchiveService archive) {
        archive.run(tenant, year);
    }
}
```
