package com.ryanheise.persistp;

import java.util.Map;
import java.util.HashMap;
import java.lang.ref.SoftReference;
import java.util.Set;
import java.util.HashSet;
import java.lang.reflect.Constructor;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.io.IOException;
import java.io.File;

/**
 * Give it a base directory and a path containing a * wildcard.
 * The * wildcard stands for the part of the path that will
 * become the key in the map.
 */
public class EntityMap<X extends Entity> extends AbstractMap<String, X> {
	public static <X extends Entity> EntityMap<X> create(Class<X> entityClass, String filePattern) throws IOException {
		return create(null, entityClass, new File(filePattern));
	}

	public static <X extends Entity> EntityMap<X> create(Entity parent, Class<X> entityClass, String filePattern) throws IOException {
		return new EntityMap<X>(parent, entityClass, new File(filePattern));
	}

	public static <X extends Entity> EntityMap<X> create(Class<X> entityClass, File filePattern) throws IOException {
		return create(null, entityClass, filePattern);
	}

	public static <X extends Entity> EntityMap<X> create(Entity parent, Class<X> entityClass, File filePattern) throws IOException {
		return new EntityMap<X>(parent, entityClass, filePattern);
	}

	private Entity parent;
	private Class<X> entityClass;
	private File filePattern;
	private Map<String, SoftReference<X>> entities = new HashMap<String, SoftReference<X>>();
	private EntrySet entrySet;

	private EntityMap(Entity parent, Class<X> entityClass, File filePattern) throws IOException {
		this.parent = parent;
		this.entityClass = entityClass;
		this.filePattern = filePattern;
		if (filePattern != null)
			loadKeys();
	}

	public X putEx(String key, X value) throws IOException, IllegalAccessException {
		if (filePattern == null)
			throw new IllegalStateException("Must be associated with a file first");
		X old = get(key);
		File file = substitute(key);
		value.setPropertiesFile(file);
		entities.put(key, new SoftReference<X>(value));
		value.save();
		if (parent != null) value.injectBackRef(parent);
		return old;
	}

	@Override
	public X put(String key, X value) {
		try {
			return putEx(key, value);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public X get(int key) {
		return get(String.valueOf(key));
	}

	public X put(int key, X value) {
		X old = get(key);
		put(String.valueOf(key), value);
		return old;
	}

	public X get(double key) {
		return get(String.valueOf(key));
	}

	public X put(double key, X value) {
		X old = get(key);
		put(String.valueOf(key), value);
		return old;
	}

	public X get(boolean key) {
		return get(String.valueOf(key));
	}

	public X put(boolean key, X value) {
		X old = get(key);
		put(String.valueOf(key), value);
		return old;
	}

	public boolean add(X entity) {
		try {
			put(entity.getKeyProp(), entity);
			return true;
		}
		catch (Exception e) {
			return false;
		}
	}

	public Set<Map.Entry<String, X>> entrySet() {
		Set<Map.Entry<String, X>> entrySet;
		return (entrySet = this.entrySet) == null ? (this.entrySet = new EntrySet()) : entrySet;
	}

	private final class EntrySet extends AbstractSet<Map.Entry<String, X>> {
		public final int size() { return entities.size(); }
		public final Iterator<Map.Entry<String, X>> iterator() {
			return new EntryIterator();
		}
	}

	private final class EntityEntry implements Map.Entry<String, X> {
		private Map.Entry<String, SoftReference<X>> originalEntry;

		public EntityEntry(Map.Entry<String, SoftReference<X>> originalEntry) {
			this.originalEntry = originalEntry;
		}

		public String getKey() {
			return originalEntry.getKey();
		}

		public X getValue() {
			/* SoftReference<X> ref = originalEntry.getValue(); */
			/* if (ref != null) */
			return lazyGet(getKey());
		}

		public X setValue(X entity) {
			X old = getValue();
			entities.put(getKey(), new SoftReference<X>(entity));
			return old;
		}
	}

	private final class EntryIterator implements Iterator<Map.Entry<String, X>> {
		private Iterator<Map.Entry<String, SoftReference<X>>> it = entities.entrySet().iterator();

		public final Map.Entry<String, X> next() {
			Map.Entry<String, SoftReference<X>> originalEntry = it.next();
			if (originalEntry != null) {
				return new EntityEntry(originalEntry);
			}
			return null;
		}

		public boolean hasNext() {
			return it.hasNext();
		}
	}

	public X lazyGet(String key) {
		X entity = null;
		SoftReference<X> ref = entities.get(key);
		if (ref != null) {
			entity = ref.get();
			if (entity == null) System.out.println(key + " ref is null");
		}
		if (entity == null) {
			File file = substitute(key);
			if (file.exists()) {
				try {
					entity = entityClass.newInstance();
					entity.load(file);
					entity.setKeyProp(key);
					if (parent != null) entity.injectBackRef(parent);
					entities.put(key, new SoftReference<X>(entity));
				}
				catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}
		return entity;
	}

	private File substitute(String key) {
		return new File(filePattern.getPath().replace("*", key));
	}

	public Set<String> keySet() {
		return entities.keySet();
	}

	private void loadKeys() throws IOException {
		// /a/b/c/d/e*f/g/h/i
		File starFile = filePattern.getCanonicalFile();
		while (starFile != null && !starFile.getName().contains("*"))
			starFile = starFile.getParentFile();
		if (starFile == null)
			throw new IllegalArgumentException("filePattern must contain *");
		// starFile contains *
		String pattern = starFile.getName();
		String beforeStar = pattern.substring(0, pattern.indexOf('*'));
		String afterStar = pattern.substring(pattern.indexOf('*') + 1);
		File directory = starFile.getParentFile();
		//System.out.println("starFile = " + starFile);
		//System.out.println("directory = " + directory);
		directory.mkdirs();
		for (File file : directory.listFiles()) {
			String name = file.getName();
			String part = name;
			if (part.startsWith(beforeStar)) {
				part = part.substring(beforeStar.length());
				if (part.endsWith(afterStar)) {
					part = part.substring(0, part.length() - afterStar.length());
					entities.put(part, null);
				}
			}
		}
	}
}
