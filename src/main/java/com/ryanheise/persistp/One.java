package com.ryanheise.persistp;

import java.io.File;
import java.io.IOException;

public class One<X extends Entity> implements EntityContainer<X> {
	private Entity parent;
	private Class<X> entityClass;
	private File filePattern;
	private X entity;

	public One(Entity parent, Class<X> entityClass) {
		this.parent = parent;
		this.entityClass = entityClass;
	}

	@Override
	public boolean isBound() {
		return filePattern != null;
	}

	@Override
	public Entity getParent() {
		return parent;
	}

	@Override
	public File substitute(String key) {
		return filePattern;
	}

	@Override
	public boolean isPropertiesFormat() {
		return filePattern.getName().endsWith(".properties");
	}

	@Override
	public void removeEntity(String key) {
		entity = null;
	}

	@Override
	public void putEntity(X entity) throws IOException {
		this.entity = entity;
	}

	@Override
	public void rekeyEntity(String oldKey, String newKey) throws IOException {
	}

	// called by parent entity as soon as the file is known
	void bind(File filePattern) throws IOException {
		this.filePattern = filePattern;
	}

	public X get() {
		try {
			if (entity == null) {
				File file = filePattern;
				if (file.exists()) {
					X entity = entityClass.newInstance();
					entity.load(this);
					this.entity = entity;
				}
			}
			return entity;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
