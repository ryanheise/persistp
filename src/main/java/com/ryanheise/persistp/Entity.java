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
	private File file;
	private Properties props = new Properties();
	private Map<Field,SimpleDateFormat> dateFormats = new HashMap<Field,SimpleDateFormat>();
	private Field keyField;
	private EntityMap<? extends Entity> parentMap;

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
					else
						throw new IllegalStateException(fieldType + " field doesn't support @FPattern");
				}
			}
		}
		catch (ParseException|IOException|IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		if (keyField == null)
			throw new RuntimeException("Entity must have a field annotated with @Key");
	}

	void load(EntityMap<? extends Entity> parentMap, String key) throws IOException, IllegalAccessException, ParseException {
		setKeyProp(key);
		setParent(parentMap);
		try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
			if (isPropertiesFormat())
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
		catch (IllegalAccessException|ParseException e) {
			throw new IOException(e);
		}
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
		if (file == null)
			throw new IllegalStateException("Entity needs to be associated with a file before saving");
		file.getCanonicalFile().getParentFile().mkdirs();
		
		try {
			Class klass = getClass();
			for (Field field : klass.getDeclaredFields()) {
				field.setAccessible(true);
				String propName = propName(field);
				if (propName == null) continue;
				Object value = field.get(this);
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
			try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
				if (isPropertiesFormat())
					props.store(out, "");
				else
					props.storeToXML(out, "");
			}
		}
		catch (IllegalAccessException e) {
			throw new IOException(e);
		}
	}

	/* This would be nice to have, but not until we also provide a similarly convenient
	 * way to delete an entity from a list.
	public synchronized <Y extends Entity> void saveTo(List<Y> plainParentList) throws IOException {
		saveTo(plainParentList.size(), plainParentList);
	}

	public synchronized <Y extends Entity> void saveTo(int i, List<Y> plainParentList) throws IOException {
		EntityList<Y> parentList = (EntityList<Y>)plainParentList;
		EntityMap<Y> parentMap = parentList.getMap();
		saveTo(parentMap);
		parentList.add(i, (Y)this);
		parentList.getParent().save();
	}
	*/

	public synchronized <Y extends Entity> void saveTo(Map<String, Y> plainParentMap) throws IOException {
		EntityMap<Y> parentMap = (EntityMap<Y>)plainParentMap;
		try {
			setParent(parentMap);
			save();
			parentMap.putEx(getKeyProp(), (Y)this);
		}
		catch (IllegalAccessException e) {
			throw new IOException(e);
		}
	}

	public synchronized void delete() throws IOException {
		if (parentMap != null)
			parentMap.removeKey(getKeyProp());
		File current = file.getCanonicalFile();
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

	File getPropertiesFile() {
		return file;
	}

	private boolean isPropertiesFormat() {
		return file.getName().endsWith(".properties");
	}

	String getKeyProp() {
		try {
			keyField.setAccessible(true);
			return String.valueOf(keyField.get(this));
		}
		catch (IllegalAccessException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private void setKeyProp(String key) throws IllegalAccessException, IOException, ParseException {
		if (keyField != null)
			setField(keyField, key);
	}

	// must set the key prop before calling this.
	//
	// called before saving for the first time and before loading
	// sets the parent map, parent entity and file
	// Also binds the fpattern fields and sets the back reference
	private void setParent(EntityMap<? extends Entity> parentMap) throws IllegalAccessException, IOException {
		Entity parent = parentMap.getParent();
		this.parentMap = parentMap;
		file = parentMap.substitute(getKeyProp());
		Class klass = getClass();
		for (Field field : klass.getDeclaredFields()) {
			field.setAccessible(true);
			Class fieldType = field.getType();
			FPattern fPattern = field.getAnnotation(FPattern.class);
			if (fPattern != null) {
				File filePattern = new File(file.getParent(), fPattern.value());
				if (fieldType == Map.class) {
					EntityMap<? extends Entity> map = (EntityMap<? extends Entity>)field.get(this);
					map.bind(filePattern);
				}
				else if (fieldType == List.class) {
					EntityList<? extends Entity> list = (EntityList<? extends Entity>)field.get(this);
					list.bind(filePattern);
				}
			}
			if (field.getAnnotation(BackRef.class) != null && parent != null && fieldType == parent.getClass()) {
				field.set(this, parent);
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

	private void loadList(Field field) throws IllegalAccessException, IOException, ParseException {
		ParameterizedType pType = (ParameterizedType)field.getGenericType(); 
		Class<? extends Entity> elementType = (Class<? extends Entity>)pType.getActualTypeArguments()[0]; 
		EntityList<? extends Entity> list = EntityList.create(this, elementType);
		field.setAccessible(true);
		field.set(this, list);
	}

	private void loadMap(Field field) throws IllegalAccessException, IOException {
		ParameterizedType pType = (ParameterizedType)field.getGenericType(); 
		Class<? extends Entity> elementType = (Class<? extends Entity>)pType.getActualTypeArguments()[1]; 
		EntityMap<? extends Entity> map = EntityMap.instance(this, elementType, (File)null);
		field.setAccessible(true);
		field.set(this, map);
	}

	private void storeInt(Field field) throws IllegalAccessException {
		props.setProperty(propName(field), String.valueOf(field.get(this)));
	}

	private void storeDouble(Field field) throws IllegalAccessException {
		props.setProperty(propName(field), String.valueOf(field.get(this)));
	}

	private void storeBoolean(Field field) throws IllegalAccessException {
		props.setProperty(propName(field), String.valueOf(field.get(this)));
	}

	private void storeString(Field field) throws IllegalAccessException {
		props.setProperty(propName(field), (String)field.get(this));
	}

	private void storeDate(Field field) throws IllegalAccessException, IOException {
		SimpleDateFormat df = df(field);
		props.setProperty(propName(field), df.format((Date)field.get(this)));
	}

	/** This performs a shallow store of the key list only. It assumes each element has already been stored. */
	private void storeList(Field field) throws IllegalAccessException {
		String propName = propName(field);
		EntityList<? extends Entity> list = (EntityList<? extends Entity>)field.get(this);
		StringBuilder listStr = new StringBuilder();
		for (Entity entity : list) {
			if (listStr.length() == 0)
				listStr.append(entity.getKeyProp());
			else
				listStr.append(",").append(entity.getKeyProp());
		}
		props.setProperty(propName, listStr.toString());
	}

	private void setField(Field field, String s) throws IllegalAccessException, IOException, ParseException {
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
			EntityList<? extends Entity> list = (EntityList<? extends Entity>)field.get(this);
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
