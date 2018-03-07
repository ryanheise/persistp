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
import java.util.stream.Collectors;
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
				Class fieldType = field.getType();
				FPattern fPattern = field.getAnnotation(FPattern.class);
				if (fPattern != null) {
					if (fieldType == Map.class)
						initEntityMap(field);
					else if (fieldType == List.class)
						initEntityList(field);
					else if (fieldType == One.class)
						initOne(field);
					else
						throw new IllegalStateException(fieldType + " field doesn't support @FPattern");
				}
				else {
					Prop prop = field.getAnnotation(Prop.class);
					if (prop != null) {
						if (fieldType == List.class) {
							try {
								ParameterizedType pType = (ParameterizedType)field.getGenericType(); 
								Class<?> elementType = (Class<?>)pType.getActualTypeArguments()[0]; 
								if (elementType == String.class)
									field.set(this, new ArrayList<String>());
								else if (elementType == Integer.class)
									field.set(this, new ArrayList<Integer>());
								else
									throw new IllegalStateException(fieldType + " field has unsupported type for @Prop");
							}
							catch (IllegalAccessException e) {
								throw new RuntimeException(e);
							}
						}
					}
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
		File file = getEntityFile();
		if (file.isDirectory()) {
			// Nothing to load, and key has already been set
		}
		else {
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
					if (propName != null) {
						String s = props.getProperty(propName);
						// If the property is not present, use the initial value as the default
						if (s == null) {
							Prop prop = field.getAnnotation(Prop.class);
							if (prop != null) {
								String initial = prop.initial();
								if (initial != null && initial.length() > 0)
									s = initial;
							}
						}
						setField(field, s);
					}
				}
			}
			catch (ParseException e) {
				throw new IOException(e);
			}
		}
	}

	protected String getProperty(String key) {
		return props.getProperty(key);
	}

	protected String getProperty(String key, String def) {
		return props.getProperty(key, def);
	}

	protected void setProperty(String key, String value) {
		props.setProperty(key, value);
	}

	protected void removeProperty(String key) {
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

	private boolean isPropertiesFormat() throws IOException {
		return getEntityFile().getName().endsWith(".properties");
	}

	private boolean isXmlFormat() throws IOException {
		return getEntityFile().getName().endsWith(".xml");
	}

	private boolean isDirectoryFormat() throws IOException {
		return !isPropertiesFormat() && !isXmlFormat();
	}

	// parent must be set before saving
	public synchronized void save() throws IOException {
		if (parentContainer == null)
			throw new IllegalStateException("saveTo() required on first save");
		
		Class klass = getClass();
		if (!isDirectoryFormat()) {
			// Put the field values into props
			for (Field field : klass.getDeclaredFields()) {
				field.setAccessible(true);
				String propName = propName(field);
				if (propName == null) continue;
				Object value = getField(field);
				Class fieldType = field.getType();
				if (fieldType == Integer.TYPE)
					storeAsString(field);
				else if (fieldType == Long.TYPE)
					storeAsString(field);
				else if (fieldType == Double.TYPE)
					storeAsString(field);
				else if (fieldType == Boolean.TYPE)
					storeAsString(field);
				else if (fieldType == Integer.class)
					storeAsStringOrNull(field);
				else if (fieldType == Long.class)
					storeAsStringOrNull(field);
				else if (fieldType == Double.class)
					storeAsStringOrNull(field);
				else if (fieldType == Boolean.class)
					storeAsStringOrNull(field);
				else if (fieldType == String.class)
					storeStringOrNull(field);
				else if (fieldType == Date.class)
					storeDate(field);
				else if (fieldType == List.class)
					storeList(field);
				else
					throw new IOException("Unsupported type " + fieldType);
			}
		}
		// If this entity's key has changed, the file needs to be renamed
		String newKey = getKeyFieldValue();
		if (key != null && !key.equals(newKey)) {
			parentContainer.rekeyEntity(key, newKey);
			rebind();
		}
		else {
			getEntityFile().getParentFile().mkdirs();
		}
		key = newKey;
		File file = getEntityFile();
		if (isDirectoryFormat()) {
			// Create the directory
			file.mkdirs();
		}
		else {
			// write out the properties to the file
			try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
				if (isPropertiesFormat())
					props.store(out, "");
				else
					props.storeToXML(out, "");
			}
		}
	}

	void rebind() throws IOException {
		Class klass = getClass();
		// invalidate child maps based on old file location
		// and point them to the new location
		for (Field field : klass.getDeclaredFields()) {
			field.setAccessible(true);
			FPattern fPattern = field.getAnnotation(FPattern.class);
			if (fPattern != null) {
				File filePattern = new File(getEntityDirectory(), fPattern.value());
				Class fieldType = field.getType();
				if (fieldType == Map.class) {
					EntityMap<? extends Entity> map = (EntityMap<? extends Entity>)getField(field);
					map.rebind(filePattern);
				}
				else if (fieldType == List.class) {
					EntityList<? extends Entity> list = (EntityList<? extends Entity>)getField(field);
					list.rebind(filePattern);
				}
				else if (fieldType == One.class) {
					One<? extends Entity> one = (One<? extends Entity>)getField(field);
					one.rebind(filePattern);
				}
			}
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
				File filePattern = new File(getEntityDirectory(), fPattern.value());
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
		parentContainer.removeEntity(getKeyFieldValue());
		File current = getEntityFile().getCanonicalFile();
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

	String getKeyFieldValue() {
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

	protected File getEntityFile() throws IOException {
		return parentContainer.substitute(getKeyFieldValue()).getCanonicalFile();
	}

	protected File getEntityDirectory() throws IOException {
		return isDirectoryFormat() ? getEntityFile() : getEntityFile().getParentFile();
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
				File filePattern = new File(getEntityDirectory(), fPattern.value());
				if (fieldType == Map.class) {
					//EntityMap<? extends Entity> map = (EntityMap<? extends Entity>)getField(field);
					//map.bind(filePattern);
					// It is safe to reinitialise the map field. The map would have been
					// empty because entities cannot be added to an EntityMap until it's
					// filePattern is known.
					initEntityMap(field, filePattern);
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

	private void initEntityList(Field field) throws IOException {
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

	private void initEntityMap(Field field) throws IOException {
		initEntityMap(field, null);
	}

	private void initEntityMap(Field field, File filePattern) throws IOException {
		try {
			ParameterizedType pType = (ParameterizedType)field.getGenericType(); 
			Class<? extends Entity> elementType = (Class<? extends Entity>)pType.getActualTypeArguments()[1]; 
			EntityMap<? extends Entity> map = EntityMap.instance(this, elementType, filePattern);
			field.setAccessible(true);
			field.set(this, map);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private void initOne(Field field) throws IOException {
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

	private void storeAsString(Field field) {
		props.setProperty(propName(field), String.valueOf(getField(field)));
	}

	private void storeAsStringOrNull(Field field) {
		String key = propName(field);
		Object value = getField(field);
		if (value != null)
			props.setProperty(key, String.valueOf(value));
		else
			props.remove(key);
	}

	private void storeStringOrNull(Field field) {
		String key = propName(field);
		String value = (String)getField(field);
		if (value != null)
			props.setProperty(key, value);
		else
			props.remove(key);
	}

	private void storeDate(Field field) throws IOException {
		SimpleDateFormat df = df(field);
		props.setProperty(propName(field), df.format((Date)getField(field)));
	}

	/** This performs a shallow store of the key list only. It assumes each element has already been stored. */
	private void storeList(Field field) {
		String propName = propName(field);
		ParameterizedType pType = (ParameterizedType)field.getGenericType(); 
		Class<?> elementType = (Class<?>)pType.getActualTypeArguments()[0]; 
		if (Entity.class.isAssignableFrom(elementType)) {
			EntityList<? extends Entity> list = (EntityList<? extends Entity>)getField(field);
			props.setProperty(propName, String.join(",", list.stream().map(Entity::getKeyFieldValue).collect(Collectors.toList())));
		}
		else {
			List<?> list = (List<?>)getField(field);
			props.setProperty(propName, String.join(",", list.stream().map(String::valueOf).collect(Collectors.toList())));
		}
	}

	private int parseInt(String s, int def) {
		try {
			return Integer.parseInt(s);
		}
		catch (Exception e) {
			return def;
		}
	}

	private long parseLong(String s, long def) {
		try {
			return Long.parseLong(s);
		}
		catch (Exception e) {
			return def;
		}
	}

	private double parseDouble(String s, double def) {
		try {
			return Double.parseDouble(s);
		}
		catch (Exception e) {
			return def;
		}
	}

	private void setField(Field field, String s) throws IOException, ParseException {
		try {
			Class fieldType = field.getType();
			if (fieldType == Integer.TYPE)
				field.setInt(this, parseInt(s, 0));
			else if (fieldType == Long.TYPE)
				field.setLong(this, parseLong(s, 0L));
			else if (fieldType == Double.TYPE)
				field.setDouble(this, parseDouble(s, 0.0));
			else if (fieldType == Boolean.TYPE)
				field.setBoolean(this, Boolean.parseBoolean(s != null ? s : "false"));
			else if (fieldType == Integer.class)
				field.set(this, (s == null || s.isEmpty()) ? null : new Integer(parseInt(s, 0)));
			else if (fieldType == Long.class)
				field.set(this, (s == null || s.isEmpty()) ? null : new Long(parseLong(s, 0L)));
			else if (fieldType == Double.class)
				field.set(this, (s == null || s.isEmpty()) ? null : new Double(parseDouble(s, 0.0)));
			else if (fieldType == Boolean.class)
				field.set(this, (s == null || s.isEmpty()) ? null : new Boolean(s));
			else if (fieldType == String.class)
				field.set(this, s != null ? s : "");
			else if (fieldType == Date.class) {
				final SimpleDateFormat df = df(field);
				field.set(this, df.parse(s != null ? s : df.format(new Date())));
			}
			else if (fieldType == List.class) {
				List<String> keys = new ArrayList<String>();
				String keysStr = (s != null ? s : "").trim();
				String[] keysArray = keysStr.length() == 0 ? new String[0] : keysStr.split(", *");
				for (String key : keysArray)
					keys.add(key);
				ParameterizedType pType = (ParameterizedType)field.getGenericType(); 
				Class<?> elementType = (Class<?>)pType.getActualTypeArguments()[0]; 
				if (Entity.class.isAssignableFrom(elementType)) {
					EntityList<? extends Entity> list = (EntityList<? extends Entity>)getField(field);
					list.setKeys(keys);
				}
				else if (elementType == String.class) {
					List<String> list = (List<String>)getField(field);
					list.clear();
					list.addAll(keys);
				}
				else if (elementType == Integer.class) {
					List<Integer> list = (List<Integer>)getField(field);
					list.clear();
					list.addAll(keys.stream().map(Integer::new).collect(Collectors.toList()));
				}
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
