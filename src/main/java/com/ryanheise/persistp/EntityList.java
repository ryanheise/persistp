package com.ryanheise.persistp;

import java.util.ArrayList;
import java.lang.ref.SoftReference;
import java.util.AbstractList;
import java.io.File;
import java.lang.reflect.Constructor;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public class EntityList<X extends Entity> extends AbstractList<X> {
	static <X extends Entity> EntityList<X> create(Entity parent, Class<X> entityClass) throws IOException {
		return new EntityList<X>(parent, entityClass);
	}

	private Entity parent;
	private Class<X> entityClass;
	private List<String> keys;
	private File filePattern;
	private EntityMap<X> map;

	private EntityList(Entity parent, Class<X> entityClass) throws IOException {
		this.parent = parent;
		this.entityClass = entityClass;
		map = EntityMap.instance(parent, entityClass, filePattern);
		keys = new ArrayList<String>();
	}

	// called by parent entity as soon as the file is known
	void bind(File filePattern) throws IOException {
		this.filePattern = filePattern;
		map = EntityMap.instance(parent, entityClass, filePattern);
	}

	void rebind(File filePattern) throws IOException {
		bind(filePattern);
	}

	void setKeys(List<String> keys) {
		 this.keys = keys;
	}

	Entity getParent() {
		return parent;
	}

	File getFilePattern() {
		return filePattern;
	}

	EntityMap<X> getMap() {
		return map;
	}

	public X get(int i) {
		String key = keys.get(i);
		X entity = map.get(key);
		return entity;
	}

	@Override
	public void add(int i, X entity) {
		String id = entity.getKeyProp();
		keys.add(i, id);
	}

	@Override
	public X remove(int i) {
		X old = get(i);
		keys.remove(i);
		return old;
	}

	@Override
	public int size() {
		return keys.size();
	}

	@Override
	public int hashCode() {
		return size(); // Because the list is lazy, don't load the values.
	}
}
