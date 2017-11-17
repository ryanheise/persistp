package com.ryanheise.persistp;

import java.util.ArrayList;
import java.lang.ref.SoftReference;
import java.util.AbstractList;
import java.io.File;
import java.lang.reflect.Constructor;
import java.io.IOException;
import java.util.Iterator;
import java.util.stream.Collectors;
import java.util.List;

public class EntityList<X extends Entity> extends ArrayList<X> { //extends AbstractList<X> {
	static <X extends Entity> EntityList<X> create(Entity parent, Class<X> entityClass) throws IOException {
		return new EntityList<X>(parent, entityClass);
	}

	private Entity parent;
	private Class<X> entityClass;
	private File filePattern;
	private EntityMap<X> map;

	private EntityList(Entity parent, Class<X> entityClass) throws IOException {
		this.parent = parent;
		this.entityClass = entityClass;
		map = EntityMap.instance(parent, entityClass, filePattern);
	}

	// called by parent entity as soon as the file is known
	void bind(File filePattern) throws IOException {
		this.filePattern = filePattern;
		map = EntityMap.instance(parent, entityClass, filePattern);
	}

	void rebind(File filePattern) throws IOException {
		this.filePattern = filePattern;
		map.rebind(filePattern);
	}

	void setKeys(List<String> keys) {
		clear();
		addAll(keys.stream().map(key -> map.get(key)).collect(Collectors.toList()));
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
}
