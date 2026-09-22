```java
public static List<Author> allAuthors(@FromServices AuthorRepository authors) {
    return authors.findAll();
}
```
