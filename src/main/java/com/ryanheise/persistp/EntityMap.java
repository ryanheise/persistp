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
import java.util.ArrayList;
import java.util.List;
import java.io.File;

/**
 * Give it a base directory and a path containing a * wildcard.
 * The * wildcard stands for the part of the path that will
 * become the key in the map.
 */
public class EntityMap<X extends Entity> extends AbstractMap<String, X> implements EntityContainer<X> {
	private static Map<File, SoftReference<EntityMap<? extends Entity>>> cache = new HashMap<File, SoftReference<EntityMap<? extends Entity>>>();

	public static synchronized <Y extends Entity> EntityMap<Y> instance(Class<Y> entityClass, String filePattern) throws IOException {
		return instance(null, entityClass, new File(filePattern));
	}

	public static synchronized <Y extends Entity> EntityMap<Y> instance(Entity parent, Class<Y> entityClass, String filePattern) throws IOException {
		return new EntityMap<Y>(parent, entityClass, new File(filePattern));
	}

	public static synchronized <Y extends Entity> EntityMap<Y> instance(Class<Y> entityClass, File filePattern) throws IOException {
		return instance(null, entityClass, filePattern);
	}

	public static synchronized <Y extends Entity> EntityMap<Y> instance(Entity parent, Class<Y> entityClass, File filePattern) throws IOException {
		EntityMap<Y> map = null;
		SoftReference<EntityMap<? extends Entity>> mapRef = cache.get(filePattern);
		if (mapRef != null)
			map = (EntityMap<Y>)mapRef.get();
		if (map == null) {
			map = new EntityMap<Y>(parent, entityClass, filePattern);
			if (filePattern != null)
				cache(map);
		}
		return map;
	}

	private static synchronized <Y extends Entity> void cache(EntityMap<Y> entityMap) {
		cache.put(entityMap.filePattern, new SoftReference(entityMap));
	}

	private static synchronized <Y extends Entity> void renameInCache(EntityMap<Y> entityMap, File filePattern) {
		cache.remove(entityMap.filePattern);
		entityMap.filePattern = filePattern;
		cache(entityMap);
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
	}

	void delete() throws IOException {
		for (X entity : new ArrayList<X>(values())) {
			entity.delete();
		}
	}

	@Override
	public boolean isBound() {
		return filePattern != null;
	}

	// called by parent entity as soon as the file is known
	void bind(File filePattern) throws IOException {
		this.filePattern = filePattern;
		cache(this);
	}

	void rebind(File filePattern) throws IOException {
		if (filePattern.equals(this.filePattern))
			return;
		renameInCache(this, filePattern);
		for (SoftReference<X> ref : entities.values()) {
			if (ref != null) {
				X entity = ref.get();
				if (entity != null)
					entity.rebind();
			}
		}
	}

	File getFilePattern() {
		return filePattern;
	}

	@Override
	public Entity getParent() {
		return parent;
	}

	@Override
	public void removeEntity(String key) {
		remove(key);
	}

	@Override
	public void putEntity(X entity) throws IOException {
		putEx(entity.getKeyFieldValue(), entity);
	}

	void putEx(String key, X value) throws IOException {
		if (filePattern == null)
			throw new IllegalStateException("Must be associated with a file first");
		entities.put(key, new SoftReference<X>(value));
	}

	@Override
	public X put(String key, X value) {
		throw new UnsupportedOperationException();
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
			put(entity.getKeyFieldValue(), entity);
			return true;
		}
		catch (Exception e) {
			return false;
		}
	}

	@Override
	public X remove(Object key) {
		X old = null;
		SoftReference<X> oldRef = entities.remove(key);
		if (oldRef != null)
			old = oldRef.get();
		// XXX: If old is still null, should I load it before removing it to abide with the semantics of remove()?
		return old;
	}

	@Override
	public int hashCode() {
		return size(); // Because the map is lazy, don't load the values.
	}

	@Override
	public Set<Map.Entry<String, X>> entrySet() {
		Set<Map.Entry<String, X>> entrySet;
		return (entrySet = this.entrySet) == null ? (this.entrySet = new EntrySet()) : entrySet;
	}

	@Override
	public boolean containsKey(Object key) {
		return get(key) != null;
	}

	@Override
	public X get(Object key) {
		return lazyGet((String)key);
	}

	private final class EntrySet extends AbstractSet<Map.Entry<String, X>> {
		public final int size() { return keyCount(); }
		public final Iterator<Map.Entry<String, X>> iterator() {
			return new EntryIterator();
		}
	}

	private final class EntityEntry implements Map.Entry<String, X> {
		private String key;

		public EntityEntry(String key) {
			this.key = key;
		}

		public String getKey() {
			return key;
		}

		public X getValue() {
			return lazyGet(getKey());
		}

		public X setValue(X entity) {
			X old = getValue();
			entities.put(getKey(), new SoftReference<X>(entity));
			return old;
		}
	}

	private final class EntryIterator implements Iterator<Map.Entry<String, X>> {
		private List<String> keys = new ArrayList<String>();
		private int i = -1;

		public EntryIterator() {
			try {
				// /a/b/c/d/e*f/g/h/i
				File starFile = getStarFile();
				// starFile contains *
				String pattern = starFile.getName();
				int starIdx = pattern.indexOf('*');
				String beforeStar = pattern.substring(0, starIdx);
				String afterStar = pattern.substring(starIdx + 1);
				File directory = starFile.getParentFile();
				if (directory.exists()) {
					for (File file : directory.listFiles()) {
						String name = file.getName();
						String part = name;
						if (part.startsWith(beforeStar)) {
							part = part.substring(beforeStar.length());
							if (part.endsWith(afterStar)) {
								part = part.substring(0, part.length() - afterStar.length());
								if (substitute(part).exists())
									keys.add(part);
							}
						}
					}
				}
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		public final Map.Entry<String, X> next() {
			return new EntityEntry(keys.get(++i));
		}

		@Override
		public boolean hasNext() {
			return i + 1 < keys.size();
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
					entity.load(this, key);
					entities.put(key, new SoftReference<X>(entity));
				}
				catch (RuntimeException e) {
					throw e;
				}
				catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}
		return entity;
	}

	@Override
	public File substitute(String key) {
		return new File(filePattern.getPath().replace("*", key));
	}

	@Override
	public void rekeyEntity(String oldKey, String newKey) throws IOException {
		X entity = get(oldKey);
		File oldFile = substituteStarFile(oldKey);
		File newFile = substituteStarFile(newKey);
		if (!oldFile.renameTo(newFile))
			throw new IOException("Failed to rename " + oldFile + " to " + newFile);
		putEntity(entity);
		removeEntity(oldKey);
	}

	File getStarFile() throws IOException {
		File starFile = filePattern.getCanonicalFile();
		while (starFile != null && !starFile.getName().contains("*"))
			starFile = starFile.getParentFile();
		if (starFile == null)
			throw new IllegalArgumentException("filePattern must contain *");
		return starFile;
	}

	File substituteStarFile(String key) throws IOException {
		return new File(getStarFile().getPath().replace("*", key));
	}

	private int keyCount() {
		try {
			int count = 0;
			// /a/b/c/d/e*f/g/h/i
			File starFile = getStarFile();
			// starFile contains *
			String pattern = starFile.getName();
			int starIdx = pattern.indexOf('*');
			String beforeStar = pattern.substring(0, starIdx);
			String afterStar = pattern.substring(starIdx + 1);
			File directory = starFile.getParentFile();
			if (directory.exists()) {
				for (File file : directory.listFiles()) {
					String name = file.getName();
					String part = name;
					if (part.startsWith(beforeStar)) {
						part = part.substring(beforeStar.length());
						if (part.endsWith(afterStar)) {
							part = part.substring(0, part.length() - afterStar.length());
							if (substitute(part).exists())
								count++;
						}
					}
				}
			}
			return count;
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
