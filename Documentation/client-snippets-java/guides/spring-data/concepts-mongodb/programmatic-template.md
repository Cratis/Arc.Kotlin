```java
static TextRows textRows(MongoClient client, String database, MongoCustomConversions conversions) {
    var mappingContext = new MongoMappingContext();
    mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    mappingContext.setInitialEntitySet(Set.of(UuidRow.class, TextRow.class, LongRow.class));
    mappingContext.afterPropertiesSet();

    var factory = new SimpleMongoClientDatabaseFactory(client, database);
    var converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), mappingContext);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();

    var template = new MongoTemplate(factory, converter);
    return new MongoRepositoryFactory(template).getRepository(TextRows.class);
}
```
