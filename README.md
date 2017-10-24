# PersistP

PersistP is a simple library for loading properties files into Java objects
and vice versa. It supports one-to-many relationships with lazy loading.

## Usage

### Define an Entity

Make your class extend `Entity` and use the `@Prop` annotation on each field that you
want to be persisted.

```java
public class Book extends Entity {
	@Key String id;
	@Prop String title;
	@Prop double price;
	@Prop @Temporal("yyyy/MM/dd") Date published;
	@Prop("sellCount") int sold;

	// Needed when loading from a file
	public Book() {
	}

	public Book(String id, String title, double price, Date published) {
		this.id = id;
		this.title = title;
		this.price = price;
		this.published = published;
	}
}
```

Notes:

* Use `@Prop("foo")` to bind a field to a property named `foo`. The default property name is the same as the field name.
* Use `@Key` to specify the *unique primary key* for this entity. This key is required and is used in the naming of the file. You can also add `@Prop` if you want the key to be stored as a property within the file.
* Use the `@Temporal` annotation on any Date specifying the format.

### Create an `EntityMap`

```java
EntityMap<Book> books = EntityMap.instance(Book.class, new File("*.properties"));
```

Or,

```java
EntityMap<Book> books = EntityMap.instance(Book.class, "*.properties");
```

Each file matching pattern `*.properties` represents an Entity.
The part of the file path matching `*` is the *key*.

### Load an existing entity

```java
Book book = books.get("book1");
```

The file `book1.properties` is loaded into the Java object `book`, where `book1`
is the key.

### Save a new entity

```java
Book book = new Book("Book 2", 19.99, new Date());
book.saveTo(books);
```

The book is now stored in the file `book2.properties` where `book2` is the key, and is also added to the `books` EntityMap.

### Update an existing entity

```java
book.sold++;
book.save();
```

### Delete an existing entity

```java
book.delete();
```

The associated properties file is deleted and the book is removed from the `books`
EntityMap.

It is possible to override the `delete` method if you wish to do more upon deletion.

### Implementing a one-to-many relationship as a `Map`

```java
public class Book extends Entity {
	@FPattern("authors/*.property") Map<String, Author> authors;
	...
}
```

Under the hood, an `EntityMap` is injected into the `authors` field. *Every*
author matching the pattern `authors/*.property` is loaded into the map.
Notice that the map is **not** a property and is not annotated with `@Prop`.
This is because it is a complete map without ordering, so no additional
information needs to be stored about which items to include and in what order
to include them.

```java
new Author(1, "Author 1").saveTo(authors);
new Author(2, "Author 2").saveTo(authors);
```

These two lines result in the creation of two files `authors/1.properties` and
`authors/2.properties` which are added to the `authors` map.

```java
authors.get("1").delete();
```

This deletes the author with key "1".

### Implementing a one-to-many relationship as a `List`

A book has a list of authors:

```java
public class Book extends Entity {
	@FPattern("authors/*.property") Map<String, Author> authorsMap;
	@Prop @FPattern("authors/*.property") List<Author> authors;
	...
}
```

This associates the Java field `authors` containing a list of `Author` entities
to the property `author` containing a comma-separated list of keys. Each entity
in the list is loaded from a file matching the pattern `authors/*.property` where
`*` is replaced by the key. The pattern is relative to the directory of the file
of the enclosing entity (e.g. relative to the parent directory of `book1.properties`).

The `Author` class MUST have a field annotated with `@Key`, and optionally may
have a field annotated with `@BackRef` which will be filled with a reference
to the parent book entity:

```java
public class Author extends Entity {
	@Key int id;
	@Prop String name;
	@BackRef Book book;

	public Author() {
	}

	public Author(int id, String name) {
		this.id = id;
		this.name = name;
	}
}
```

**Note**: Not every file matching the pattern `authors/*.property` is loaded
into the list. Only keys appearing in the `author` property in `book1.properties`
are loaded.

New entities can only be saved to the filesystem via an `EntityMap`. Saved
entities can then be added to a list. Modifications to the list property,
as with modifications to any other property, must be followed by a call
to `save()` on the entity containing that property.

```java
Author author1 = new Author(1, "Author 1");
Author author2 = new Author(2, "Author 2");
author1.saveTo(book.authorsMap);
author2.saveTo(book.authorsMap);
book.authors.add(author1);
book.authors.add(author2);
book.save();
```

The first 4 lines write the files `authors/1.properties` and
`authors/2.properties`. The last 3 updates the file `book1.properties` with
the property `authors=1,2` whose value is a comma-separate list of the keys of
the authors in the list. You MUST call `book.save()` after modifying the book's
list, otherwise the changes to the `authors` property will not be stored.

Deleting an author involves first removing it from the list and then deleting it
from the map:

```java
book.authors.remove(author1);
book.save(); // do this first to avoid a dangling reference
author1.delete();
```

The first 2 lines write the new list to `book1.properties` represented by the
property `authors=2`. The last line deletes `authors/1.properties` and removes
it from the `authorsMap` EntityMap.

### Lazy loading and soft references

To reduce the memory footprint, list and map elements are not loaded into memory
until accessed, and soft references allow elements to be reclaimed from memory
by the garbage collector if the remaining available memory is too low.

### Custom property loading/saving

An `Entity` subclass may override the `load()` and `save()` methods to to load
and save complex properties that can't be automatically managed by PersistP's
annotations. Properties can be manually read and written via the following
methods inherited `Entity` methods:

```
protected final String getProperty(String key)
protected final String getProperty(String key, String def)
protected final void setProperty(String key, String value)
protected final void removeProperty(String key)
```
