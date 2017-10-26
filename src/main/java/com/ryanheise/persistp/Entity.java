package com.ryanheise.persistp;

import java.util.Properties;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.lang.reflect.ParameterizedType;
import java.io.File;

public abstract class Entity {
	private String key;
	private Properties props = new Properties();
	private Map<Field,SimpleDateFormat> dateFormats = new HashMap<Field,SimpleDateFormat>();
	private Field keyField;
	private EntityContainer<? extends Entity> parentContainer;

	public Entity() {
		try {
			Class klass = getClass();
			for (Field field : klass.getDeclaredFields()) {
				field.setAccessible(true);
				Key key = field.getAnnotation(Key.class);
				if (key != null) {
					if (keyField != null)
						throw new IllegalStateException("Cannot have more than 1 field with @Key annotation");
					else
						keyField = field;
				}
				FPattern fPattern = field.getAnnotation(FPattern.class);
				if (fPattern != null) {
					Class fieldType = field.getType();
					if (fieldType == Map.class)
						loadMap(field);
					else if (fieldType == List.class)
						loadList(field);
					else if (fieldType == One.class)
						loadOne(field);
					else
						throw new IllegalStateException(fieldType + " field doesn't support @FPattern");
				}
			}
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	void load(One<? extends Entity> parentOne) throws IOException {
		setParent(parentOne);
		load();
	}

	void load(EntityMap<? extends Entity> parentMap, String key) throws IOException, ParseException {
		setKeyProp(key);
		setParent(parentMap);
		load();
	}

	protected void load() throws IOException {
		try (InputStream in = new BufferedInputStream(new FileInputStream(file()))) {
			if (parentContainer.isPropertiesFormat())
				props.load(in);
			else
				props.loadFromXML(in);
			Class klass = getClass();
			for (Field field : klass.getDeclaredFields()) {
				field.setAccessible(true);
				Class fieldType = field.getType();
				String propName = propName(field);
				if (propName != null)
					setField(field, props.getProperty(propName));
			}
		}
		catch (ParseException e) {
			throw new IOException(e);
		}
	}

	protected final String getProperty(String key) {
		return props.getProperty(key);
	}

	protected final String getProperty(String key, String def) {
		return props.getProperty(key, def);
	}

	protected final void setProperty(String key, String value) {
		props.setProperty(key, value);
	}

	protected final void removeProperty(String key) {
		props.remove(key);
	}

	public synchronized <X extends Entity> void saveAndAdd(List<X> list) throws IOException {
		EntityList<X> parentList = (EntityList<X>)list;
		Entity parent = parentList.getParent();
		if (parent == null)
			throw new IllegalArgumentException("List has no parent");
		EntityMap<X> parentMap = parentList.getMap();
		saveTo(parentMap);
		parentList.add((X)this);
		parent.save();
	}

	// parent must be set before saving
	public synchronized void save() throws IOException {
		if (parentContainer == null)
			throw new IllegalStateException("saveTo() required on first save");
		
		Class klass = getClass();
		for (Field field : klass.getDeclaredFields()) {
			field.setAccessible(true);
			String propName = propName(field);
			if (propName == null) continue;
			Object value = getField(field);
			Class fieldType = field.getType();
			if (fieldType == Integer.TYPE)
				storeInt(field);
			else if (fieldType == Double.TYPE)
				storeDouble(field);
			else if (fieldType == Boolean.TYPE)
				storeBoolean(field);
			else if (fieldType == String.class)
				storeString(field);
			else if (fieldType == Date.class)
				storeDate(field);
			else if (fieldType == List.class)
				storeList(field);
			else
				throw new IOException("Unsupported type " + fieldType);
		}
		// If this entity's key has changed, the file needs to be renamed
		String newKey = getKeyProp();
		if (key != null && !key.equals(newKey)) {
			parentContainer.rekeyEntity(key, newKey);
			// invalidate child maps based on old file location
			// and point them to the new location
			for (Field field : klass.getDeclaredFields()) {
				field.setAccessible(true);
				FPattern fPattern = field.getAnnotation(FPattern.class);
				if (fPattern != null) {
					File filePattern = new File(file().getParent(), fPattern.value());
					Class fieldType = field.getType();
					if (fieldType == Map.class) {
						EntityMap<? extends Entity> map = (EntityMap<? extends Entity>)getField(field);
						map.rebind(filePattern);
					}
					else if (fieldType == List.class) {
						EntityList<? extends Entity> list = (EntityList<? extends Entity>)getField(field);
						list.rebind(filePattern);
					}
				}
			}
			key = newKey;
		}
		else {
			file().getParentFile().mkdirs();
		}
		// write out the properties to the file
		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file()))) {
			if (parentContainer.isPropertiesFormat())
				props.store(out, "");
			else
				props.storeToXML(out, "");
		}
	}

	private <X extends Entity> void putEntity() throws IOException {
		EntityContainer<X> parentContainer = (EntityContainer<X>)this.parentContainer;
		parentContainer.putEntity((X)this);
	}

	/* This would be nice to have, but not until we also provide a similarly convenient
	 * way to delete an entity from a list.
	public synchronized <Y extends Entity> void saveTo(List<Y> plainParentList) throws IOException {
		saveTo(plainParentList.size(), plainParentList);
	}

	public synchronized final <Y extends Entity> void saveTo(int i, List<Y> plainParentList) throws IOException {
		EntityList<Y> parentList = (EntityList<Y>)plainParentList;
		EntityMap<Y> parentMap = parentList.getMap();
		saveTo(parentMap);
		parentList.add(i, (Y)this);
		parentList.getParent().save();
	}
	*/

	public synchronized final void saveTo(Map<String, ? extends Entity> parentContainer) throws IOException {
		saveToContainer((EntityContainer<? extends Entity>)parentContainer);
	}

	public synchronized final void saveTo(One<? extends Entity> parentContainer) throws IOException {
		saveToContainer(parentContainer);
	}

	private synchronized final void saveToContainer(EntityContainer<? extends Entity> parentContainer) throws IOException {
		if (!parentContainer.isBound())
			throw new IllegalStateException("Cannot save to unbound container");
		setParent(parentContainer);
		save();
		putEntity();
	}

	public synchronized void delete() throws IOException {
		// Delete children of this entity
		Class klass = getClass();
		for (Field field : klass.getDeclaredFields()) {
			field.setAccessible(true);
			FPattern fPattern = field.getAnnotation(FPattern.class);
			if (fPattern != null) {
				File filePattern = new File(file().getParent(), fPattern.value());
				Class fieldType = field.getType();
				if (fieldType == Map.class) {
					EntityMap<? extends Entity> map = (EntityMap<? extends Entity>)getField(field);
					map.delete();
				}
				/* In the future, List may be a quasi entity container, and we can generalise these 3 cases.
				else if (fieldType == List.class) {
					EntityList<? extends Entity> list = (EntityList<? extends Entity>)getField(field);
					list.delete();
				}
				*/
				else if (fieldType == One.class) {
					One<? extends Entity> one = (One<? extends Entity>)getField(field);
					one.delete();
				}
			}
		}

		// Delete this entity
		parentContainer.removeEntity(getKeyProp());
		File current = file().getCanonicalFile();
		do {
			if (current.isFile()) {
				if (!current.delete())
					throw new IOException("Failed to delete " + current);
			}
			else if (current.isDirectory() && current.list().length == 0) {
				if (!current.delete())
					throw new IOException("Failed to delete " + current);
			}
			else {
				break;
			}
		}
		while ((current = current.getParentFile()) != null);
	}

	String getKeyProp() {
		if (keyField == null)
			return null;
		keyField.setAccessible(true);
		return String.valueOf(getField(keyField));
	}

	private Object getField(Field field) {
		try {
			return field.get(this);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private void setKeyProp(String key) throws IOException, ParseException {
		this.key = key;
		setField(keyField, key);
	}

	private File file() throws IOException {
		return parentContainer.substitute(getKeyProp()).getCanonicalFile();
	}

	// must set the key prop before calling this.
	//
	// called before saving for the first time and before loading
	// sets the parent map, parent entity
	// Also binds the fpattern fields and sets the back reference
	private void setParent(EntityContainer<? extends Entity> parentContainer) throws IOException {
		Entity parent = parentContainer.getParent();
		this.parentContainer = parentContainer;
		Class klass = getClass();
		for (Field field : klass.getDeclaredFields()) {
			field.setAccessible(true);
			Class fieldType = field.getType();
			FPattern fPattern = field.getAnnotation(FPattern.class);
			if (fPattern != null) {
				File filePattern = new File(file().getParent(), fPattern.value());
				if (fieldType == Map.class) {
					EntityMap<? extends Entity> map = (EntityMap<? extends Entity>)getField(field);
					map.bind(filePattern);
				}
				else if (fieldType == List.class) {
					EntityList<? extends Entity> list = (EntityList<? extends Entity>)getField(field);
					list.bind(filePattern);
				}
				else if (fieldType == One.class) {
					One<? extends Entity> one = (One<? extends Entity>)getField(field);
					one.bind(filePattern);
				}
			}
			if (field.getAnnotation(BackRef.class) != null && parent != null && fieldType == parent.getClass()) {
				try {
					field.set(this, parent);
				}
				catch (IllegalAccessException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}

	private SimpleDateFormat df(Field field) throws IOException {
		SimpleDateFormat df = dateFormats.get(field);
		if (df == null) {
			Temporal temporal = field.getAnnotation(Temporal.class);
			if (temporal == null)
				throw new IOException("Date field " + field.getName() + " requires @Temporal annotation");
			df = new SimpleDateFormat(temporal.value());
			dateFormats.put(field, df);
		}
		return df;
	}

	private void loadList(Field field) throws IOException {
		try {
			ParameterizedType pType = (ParameterizedType)field.getGenericType(); 
			Class<? extends Entity> elementType = (Class<? extends Entity>)pType.getActualTypeArguments()[0]; 
			EntityList<? extends Entity> list = EntityList.create(this, elementType);
			field.setAccessible(true);
			field.set(this, list);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private void loadMap(Field field) throws IOException {
		try {
			ParameterizedType pType = (ParameterizedType)field.getGenericType(); 
			Class<? extends Entity> elementType = (Class<? extends Entity>)pType.getActualTypeArguments()[1]; 
			EntityMap<? extends Entity> map = EntityMap.instance(this, elementType, (File)null);
			field.setAccessible(true);
			field.set(this, map);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private void loadOne(Field field) throws IOException {
		try {
			ParameterizedType pType = (ParameterizedType)field.getGenericType();
			Class<? extends Entity> elementType = (Class<? extends Entity>)pType.getActualTypeArguments()[0];
			One<? extends Entity> one = new One(this, elementType);
			field.setAccessible(true);
			field.set(this, one);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private void storeInt(Field field) {
		props.setProperty(propName(field), String.valueOf(getField(field)));
	}

	private void storeDouble(Field field) {
		props.setProperty(propName(field), String.valueOf(getField(field)));
	}

	private void storeBoolean(Field field) {
		props.setProperty(propName(field), String.valueOf(getField(field)));
	}

	private void storeString(Field field) {
		props.setProperty(propName(field), (String)getField(field));
	}

	private void storeDate(Field field) throws IOException {
		SimpleDateFormat df = df(field);
		props.setProperty(propName(field), df.format((Date)getField(field)));
	}

	/** This performs a shallow store of the key list only. It assumes each element has already been stored. */
	private void storeList(Field field) {
		String propName = propName(field);
		EntityList<? extends Entity> list = (EntityList<? extends Entity>)getField(field);
		StringBuilder listStr = new StringBuilder();
		for (Entity entity : list) {
			if (listStr.length() == 0)
				listStr.append(entity.getKeyProp());
			else
				listStr.append(",").append(entity.getKeyProp());
		}
		props.setProperty(propName, listStr.toString());
	}

	private void setField(Field field, String s) throws IOException, ParseException {
		try {
			Class fieldType = field.getType();
			if (fieldType == Integer.TYPE)
				field.setInt(this, Integer.parseInt(s != null ? s : "0"));
			else if (fieldType == Double.TYPE)
				field.setDouble(this, Double.parseDouble(s != null ? s : "0.0"));
			else if (fieldType == Boolean.TYPE)
				field.setBoolean(this, Boolean.parseBoolean(s != null ? s : "false"));
			else if (fieldType == String.class)
				field.set(this, s != null ? s : "");
			else if (fieldType == Date.class) {
				final SimpleDateFormat df = df(field);
				field.set(this, df.parse(s != null ? s : df.format(new Date())));
			}
			else if (fieldType == List.class) {
				EntityList<? extends Entity> list = (EntityList<? extends Entity>)getField(field);
				List<String> keys = new ArrayList<String>();
				String keysStr = (s != null ? s : "").trim();
				String[] keysArray = keysStr.length() == 0 ? new String[0] : keysStr.split(", *");
				for (String key : keysArray)
					keys.add(key);
				list.setKeys(keys);
			}
			else
				throw new IOException("Unsupported type " + fieldType);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private String propName(Field field) {
		Prop prop = field.getAnnotation(Prop.class);
		if (prop == null) return null;
		String propName = prop.name();
		if (propName.isEmpty()) {
			propName = prop.value();
			if (propName.isEmpty())
				propName = field.getName();
		}
		return propName;
	}
}
