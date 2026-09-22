```java
MongoCustomConversions conversions = MongoCustomConversions.create(adapter -> {
    adapter.registerConverter(new UuidWrite());
    adapter.registerConverter(new UuidRead());
    adapter.registerConverter(new TextWrite());
    adapter.registerConverter(new TextRead());
    adapter.registerConverter(new LongWrite());
    adapter.registerConverter(new LongRead());
});
```
