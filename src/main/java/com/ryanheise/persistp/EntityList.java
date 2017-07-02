package com.ryanheise.persistp;

import java.util.ArrayList;
import java.lang.ref.SoftReference;
import java.util.AbstractList;
import java.io.File;
import java.lang.reflect.Constructor;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public class EntityList<X extends Entity> extends AbstractList<X> implements EntityCollection {
	public static <X extends Entity> EntityList<X> create(Class<X> entityClass, List<String> keys, File filePattern) {
		return create(null, entityClass, keys, filePattern);
	}

	public static <X extends Entity> EntityList<X> create(Entity parent, Class<X> entityClass, List<String> keys, File filePattern) {
		return new EntityList<X>(parent, entityClass, keys, filePattern);
	}

	private Entity parent;
	private Class<X> entityClass;
	private List<String> keys;
	private File filePattern;
	private ArrayList<SoftReference<X>> entities = new ArrayList<SoftReference<X>>();

	public EntityList(Entity parent, Class<X> entityClass, List<String> keys, File filePattern) {
		this.parent = parent;
		this.entityClass = entityClass;
		this.keys = keys;
		this.filePattern = filePattern;
		for (int i = 0; i < keys.size(); i++)
			entities.add(null);
	}

	public X get(int i) {
		String key = keys.get(i);
		X entity = null;
		SoftReference<X> ref = entities.get(i);
		if (ref != null) {
			entity = ref.get();
		}
		if (entity == null) {
			File file = substitute(key);
			if (file.exists()) {
				try {
					entity = entityClass.newInstance();
					entity.load(file);
					entity.setKeyProp(key);
					if (parent != null) entity.setParent(parent, this);
					entities.set(i, new SoftReference<X>(entity));
				}
				catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}
		return entity;
	}

	@Override
	public void add(int i, X entity) {
		try {
			addEx(i, entity);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	public void addEx(int i, X entity) throws IOException, IllegalAccessException {
		entities.add(i, new SoftReference<X>(entity));
		String id = entity.getKeyProp();
		keys.add(i, id);
		if (entity.getPropertiesFile() == null) {
			File file = substitute(id);
			entity.setPropertiesFile(file);
			entity.save();
		}
		if (parent != null) entity.setParent(parent, this);
	}

	@Override
	public X remove(int i) {
		X old = null;
		SoftReference<X> oldRef = entities.remove(i);
		keys.remove(i);
		if (oldRef != null)
			old = oldRef.get();
		// XXX: If old is still null, should I load it before removing it to abide with the semantics of remove()?
		return old;
	}

	@Override
	public void removeKey(String key) {
		for (Iterator<X> it = iterator(); it.hasNext();) {
			if (key.equals(it.next().getKeyProp())) {
				it.remove();
				break;
			}
		}
	}

	@Override
	public int size() {
		return keys.size();
	}

	@Override
	public int hashCode() {
		return size(); // Because the list is lazy, don't load the values.
	}

	private File substitute(String key) {
		return new File(filePattern.getPath().replace("*", key));
	}
}
