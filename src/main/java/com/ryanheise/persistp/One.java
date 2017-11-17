package com.ryanheise.persistp;

import java.io.File;
import java.io.IOException;

public class One<X extends Entity> implements EntityContainer<X> {
	private Entity parent;
	private Class<X> entityClass;
	private File filePattern;
	private X entity;

	public One(Entity parent, Class<X> entityClass, File filePattern) {
		this.parent = parent;
		this.entityClass = entityClass;
		this.filePattern = filePattern;
	}

	public One(Entity parent, Class<X> entityClass) {
		this(parent, entityClass, null);
	}

	void delete() throws IOException {
		get().delete();
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

	void rebind(File filePattern) throws IOException {
		if (filePattern.equals(this.filePattern))
			return;
		this.filePattern = filePattern;
		if (entity != null)
			entity.rebind();
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
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
