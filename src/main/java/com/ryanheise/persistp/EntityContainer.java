package com.ryanheise.persistp;

import java.io.IOException;
import java.io.File;

public interface EntityContainer<X extends Entity> {
	boolean isBound();
	Entity getParent();
	File substitute(String key);
	boolean isPropertiesFormat();
	void removeEntity(String key);
	void putEntity(X entity) throws IOException;
	void rekeyEntity(String oldKey, String newKey) throws IOException;
}
