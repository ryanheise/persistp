package com.ryanheise.persistp;

import java.util.ArrayList;
import java.lang.ref.SoftReference;
import java.util.AbstractList;
import java.io.File;
import java.lang.reflect.Constructor;
import java.io.IOException;
import java.util.List;

public class EntityList<X extends Entity> extends AbstractList<X> {
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
					if (parent != null) entity.injectBackRef(parent);
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
	}

	public void addEx(int i, X entity) throws IOException {
		entities.add(i, new SoftReference<X>(entity));
		String id = entity.getKeyProp();
		keys.add(i, id);
		File file = substitute(id);
		entity.setPropertiesFile(file);
		entity.save();
	}

	public int size() {
		return keys.size();
	}

	private File substitute(String key) {
		return new File(filePattern.getPath().replace("*", key));
	}
}
