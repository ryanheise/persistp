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
import java.io.File;

public abstract class Entity {
	private File file;
	private Properties props = new Properties();
	private Map<Field,SimpleDateFormat> dateFormats = new HashMap<Field,SimpleDateFormat>();
	private boolean useXml;
	private Field keyField;
	private Set<EntityCollection> parentCollections = new HashSet<EntityCollection>();

	public Entity() {
		try {
			Class klass = getClass();
			for (Field field : klass.getDeclaredFields()) {
				field.setAccessible(true);
				Key key = field.getAnnotation(Key.class);
				if (key != null)
					if (keyField != null)
						throw new IllegalStateException("Cannot have more than 1 field with @Key annotation");
					else
						keyField = field;
				// XXX: Reuse code in setPropertiesFile
				FPattern fPattern = field.getAnnotation(FPattern.class);
				if (fPattern == null) continue;
				Class fieldType = field.getType();
				if (fieldType == Map.class)
					loadMap(field);
				else if (fieldType == List.class)
					loadList(field);
				else
					throw new IllegalStateException(fieldType + " field doesn't support @FPattern");
			}
		}
		catch (ParseException e) {
			throw new RuntimeException(e);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	void load(File file) throws IOException {
		load(file, false);
	}

	void load(File file, boolean useXml) throws IOException {
		this.file = file;
		this.useXml = useXml;
		InputStream in = new BufferedInputStream(new FileInputStream(file));
		try {
			if (useXml)
				props.loadFromXML(in);
			else
				props.load(in);
			Class klass = getClass();
			for (Field field : klass.getDeclaredFields()) {
				Class fieldType = field.getType();
				field.setAccessible(true);
				if (fieldType == Map.class)
					loadMap(field);
				String propName = propName(field);
				if (propName == null) continue;
				String value = props.getProperty(propName);
				setField(field, value);
			}
		}
		catch (IllegalAccessException e) {
			throw new IOException(e);
		}
		catch (ParseException e) {
			throw new IOException(e);
		}
		finally {
			in.close();
		}
	}

	public synchronized void save() throws IOException {
		if (file == null)
			throw new IllegalStateException("Entity needs to be associated with a file before saving");
		file.getCanonicalFile().getParentFile().mkdirs();
		OutputStream out = null;
		try {
			Class klass = getClass();
			for (Field field : klass.getDeclaredFields()) {
				field.setAccessible(true);
				String propName = propName(field);
				if (propName == null) continue;
				Class fieldType = field.getType();
				Object value = field.get(this);
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
			out = new BufferedOutputStream(new FileOutputStream(file));
			if (useXml)
				props.storeToXML(out, "");
			else
				props.store(out, "");
		}
		catch (IllegalAccessException e) {
			throw new IOException(e);
		}
		finally {
			if (out != null)
				out.close();
		}
	}

	public synchronized void delete() throws IOException {
		for (EntityCollection collection : parentCollections)
			collection.removeKey(getKeyProp());
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

	void setPropertiesFile(File file) {
		this.file = file;
		try {
			Class klass = getClass();
			for (Field field : klass.getDeclaredFields()) {
				field.setAccessible(true);
				FPattern fPattern = field.getAnnotation(FPattern.class);
				if (fPattern == null) continue;
				Class fieldType = field.getType();
				if (fieldType == Map.class)
					loadMap(field);
				else if (fieldType == List.class)
					loadList(field);
				else
					throw new IllegalStateException(fieldType + " field doesn't support @FPattern");
			}
		}
		catch (ParseException e) {
			throw new RuntimeException(e);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	String getKeyProp() {
		try {
			return String.valueOf(keyField.get(this));
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	void setKeyProp(String key) throws IllegalAccessException, IOException, ParseException {
		if (keyField != null)
			setField(keyField, key);
	}

	<X> void setParent(Entity parent, EntityCollection collection) throws IllegalAccessException {
		parentCollections.add(collection);
		Class klass = getClass();
		for (Field field : klass.getDeclaredFields()) {
			field.setAccessible(true);
			if (field.getAnnotation(BackRef.class) == null) continue;
			Class fieldType = field.getType();
			if (fieldType == parent.getClass()) {
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
		String propName = propName(field);
		setField(field, props.getProperty(propName, ""));
	}

	private void loadMap(Field field) throws IllegalAccessException, IOException {
		FPattern fPattern = field.getAnnotation(FPattern.class);
		Class<? extends Entity> elementType = fPattern.type();
		File filePattern = file == null ? null : new File(file.getParent(), fPattern.value());
		EntityMap<? extends Entity> map = EntityMap.create(this, elementType, filePattern);
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
		System.out.println("storeList " + list);
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
			FPattern fPattern = field.getAnnotation(FPattern.class);
			Class<? extends Entity> elementType = fPattern.type();
			List<String> keys = new ArrayList<String>();
			String keysStr = (s != null ? s : "").trim();
			String[] keysArray = keysStr.length() == 0 ? new String[0] : keysStr.split(", *");
			for (String key : keysArray)
				keys.add(key);
			File filePattern = file == null ? null : new File(file.getParent(), fPattern.value());
			EntityList<? extends Entity> list = EntityList.create(this, elementType, keys, filePattern);
			field.set(this, list);
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
