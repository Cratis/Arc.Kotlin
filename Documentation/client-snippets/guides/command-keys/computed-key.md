```kotlin
@Command
class ArchiveTenantData(val tenant: String, val year: Int) : CommandKeyProvider {
    override fun commandKey(): Any = "$tenant/$year"

    suspend fun handle(@FromServices archive: ArchiveService) {
        archive.run(tenant, year)
    }
}
```
