package me.ghosthacks96.POS_System.utilities;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractConfig {
    private final String fileName;
    private ConfigManager manager;
    private Path file;
    private Map<String, Object> values = new LinkedHashMap<>();

    protected AbstractConfig(String fileName) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("Configuration file name cannot be blank");
        }
    }

    protected abstract void loadDefaults();

    public final String getFileName() {
        return fileName;
    }

    public final Path getFile() {
        if (file == null) {
            throw new IllegalStateException("Configuration has not been registered");
        }
        return file;
    }

    public final Object get(String path) {
        return copyValue(find(path));
    }

    public final String getString(String path) {
        return getString(path, null);
    }

    public final String getString(String path, String defaultValue) {
        Object value = find(path);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public final int getInt(String path) {
        return getInt(path, 0);
    }

    public final int getInt(String path, int defaultValue) {
        Object value = find(path);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public final double getDouble(String path) {
        return getDouble(path, 0.0);
    }

    public final double getDouble(String path, double defaultValue) {
        Object value = find(path);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public final boolean getBoolean(String path) {
        return getBoolean(path, false);
    }

    public final boolean getBoolean(String path, boolean defaultValue) {
        Object value = find(path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            if ("true".equalsIgnoreCase(string)) {
                return true;
            }
            if ("false".equalsIgnoreCase(string)) {
                return false;
            }
        }
        return defaultValue;
    }

    public final List<?> getList(String path) {
        Object value = find(path);
        return value instanceof List<?> list ? copyList(list) : List.of();
    }

    public final List<String> getStringList(String path) {
        Object value = find(path);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof String string) {
                strings.add(string);
            }
        }
        return strings;
    }

    public final Map<String, Object> getSection(String path) {
        Object value = find(path);
        return value instanceof Map<?, ?> map ? stringKeyedMap(map) : Map.of();
    }

    public final Map<String, Object> getValues() {
        return snapshotValues();
    }

    public final boolean contains(String path) {
        return find(path) != null;
    }

    public final void set(String path, Object value) {
        String[] parts = splitPath(path);
        Map<String, Object> section = values;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = section.get(parts[i]);
            if (!(child instanceof Map<?, ?> map)) {
                Map<String, Object> newSection = new LinkedHashMap<>();
                section.put(parts[i], newSection);
                section = newSection;
            } else {
                Map<String, Object> existingSection = stringKeyedMap(map);
                section.put(parts[i], existingSection);
                section = existingSection;
            }
        }

        if (value == null) {
            section.remove(parts[parts.length - 1]);
        } else {
            section.put(parts[parts.length - 1], copyValue(value));
        }
    }

    public final void save() {
        requireRegistered().save(this);
    }

    public final void reload() {
        requireRegistered().reload(this);
    }

    final ConfigManager getManager() {
        return manager;
    }

    final void bind(ConfigManager manager, Path file) {
        if (this.manager != null) {
            throw new IllegalStateException("Configuration is already registered");
        }
        this.manager = manager;
        this.file = file;
    }

    final void unbind() {
        manager = null;
        file = null;
    }

    final void replaceValues(Map<String, Object> values) {
        this.values = new LinkedHashMap<>(values);
    }

    final Map<String, Object> snapshotValues() {
        return stringKeyedMap(values);
    }

    private ConfigManager requireRegistered() {
        if (manager == null) {
            throw new IllegalStateException("Configuration has not been registered");
        }
        return manager;
    }

    private Object find(String path) {
        String[] parts = splitPath(path);
        Object current = values;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static String[] splitPath(String path) {
        Objects.requireNonNull(path, "path");
        String[] parts = path.split("\\.", -1);
        for (String part : parts) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("Configuration paths cannot contain empty sections");
            }
        }
        return parts;
    }

    static Map<String, Object> stringKeyedMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException("Configuration section keys must be strings");
            }
            result.put(key, copyValue(entry.getValue()));
        }
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return stringKeyedMap(map);
        }
        if (value instanceof List<?> list) {
            return copyList(list);
        }
        return value;
    }

    private static List<Object> copyList(List<?> source) {
        List<Object> result = new ArrayList<>(source.size());
        for (Object value : source) {
            result.add(copyValue(value));
        }
        return result;
    }
}
