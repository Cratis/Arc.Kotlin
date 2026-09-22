```kotlin
fun textRows(client: MongoClient, database: String, conversions: MongoCustomConversions): TextRows {
    val mappingContext = MongoMappingContext()
    mappingContext.setSimpleTypeHolder(conversions.simpleTypeHolder)
    mappingContext.setInitialEntitySet(setOf(UuidRow::class.java, TextRow::class.java, LongRow::class.java))
    mappingContext.afterPropertiesSet()

    val factory = SimpleMongoClientDatabaseFactory(client, database)
    val converter = MappingMongoConverter(DefaultDbRefResolver(factory), mappingContext)
    converter.customConversions = conversions
    converter.afterPropertiesSet()

    val template = MongoTemplate(factory, converter)
    return MongoRepositoryFactory(template).getRepository(TextRows::class.java)
}
```
