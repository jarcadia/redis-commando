package com.jarcadia.rcommando;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.jarcadia.rcommando.proxy.DaoProxy;

import io.lettuce.core.KeyValue;

public class Dao {
    
    private final RedisCommando rcommando;
    private final ValueFormatter formatter;
    private final String setKey;
    private final String hashKey;
    private final String id;

    protected Dao(RedisCommando rcommando, ValueFormatter formatter, String setKey, String id) {
        this.rcommando = rcommando;
        this.formatter = formatter;
        this.setKey = setKey;
        this.hashKey = setKey + ":" + id;
        this.id = id;
    }

    public String getSetKey() {
        return setKey;
    }

    public String getId() {
        return id;
    }
    
    public boolean exists() {
    	return rcommando.core().exists(this.hashKey) == 1L;
    }

    public <T extends DaoProxy> T as(Class<T> proxyClass) {
    	return rcommando.createObjectProxy(this, proxyClass);
    }

    public DaoValue get(String field) {
        return new DaoValue(formatter, rcommando.core().hget(hashKey, field));
    }

    public DaoValues get(String... fields) {
        List<KeyValue<String, String>> values = rcommando.core().hmget(this.hashKey, fields);
        return  new DaoValues(formatter, values.iterator());
    }

    public Optional<SetResult> set(Object... fieldsAndValues) {
    	List<String> bulkChanges = rcommando.eval()
                .cachedScript(Scripts.OBJ_SET)
                .addKeys(this.setKey, this.hashKey, this.setKey+".change")
                .addArgs(prepareArgsAsArray(fieldsAndValues))
                .returnMulti();

        if (bulkChanges.size() > 0) {
            List<Change> changes = new ArrayList<>();
            long version = Long.parseLong(bulkChanges.get(0));
            for (int i=1; i<bulkChanges.size(); i+=3) {
                Change changedValue = new Change(bulkChanges.get(i),
                        new DaoValue(formatter, bulkChanges.get(i+1)),
                        new DaoValue(formatter, bulkChanges.get(i+2)));
                changes.add(changedValue);
            }
            SetResult result = new SetResult(this, version == 1L, changes);
            rcommando.invokeChangeCallbacks(result);
            return Optional.of(result);
        } else {
            return Optional.empty();
        }

    }

    public Optional<SetResult> set(Map<String, Object> properties) {
        if (properties.isEmpty()) {
            return Optional.empty();
        } else {
        	Object[] fieldsAndValues = properties.entrySet().stream()
        		.flatMap(entry -> Stream.of(entry.getKey(), entry.getValue()))
        		.collect(Collectors.toList())
        		.toArray(new Object[0]);
            return this.set(fieldsAndValues);
        }
    }

    public boolean touch() {
        boolean created = rcommando.eval()
            .cachedScript(Scripts.OBJ_TOUCH)
            .addKeys(this.setKey, this.hashKey, this.setKey + ".change")
            .returnLong() == 1L;
        if (created) {
            rcommando.invokeObjectInsertCallbacks(this);
        }
        return created;
    }

    public boolean delete() {
        int numDeleted = rcommando.eval()
                .cachedScript(Scripts.OBJ_CHECKED_DELETE)
                .addKeys(this.setKey, this.hashKey, this.setKey + ".change")
                .addArgs(this.id)
                .returnInt();
        
        if (numDeleted == 1) {
            rcommando.invokeDeleteCallbacks(setKey, id);
            return true;
        } else {
            return false;
        }
    }

    public Optional<SetResult> clear(String... fields) {
        List<String> bulkChanges = rcommando.eval()
                .cachedScript(Scripts.OBJ_CLEAR_FIELD)
                .addKeys(this.setKey, this.hashKey, this.setKey+".change")
                .addArgs(fields)
                .returnMulti();

        if (bulkChanges.size() > 0) {
            List<Change> changes = new ArrayList<>();
            long version = Long.parseLong(bulkChanges.get(0));
            for (int i=1; i<bulkChanges.size(); i+=2) {
                Change changedValue = new Change(bulkChanges.get(i),
                        new DaoValue(formatter, bulkChanges.get(i+1)),
                        new DaoValue(formatter, null));
                changes.add(changedValue);
            }
            SetResult result = new SetResult(this, false, changes);
            rcommando.invokeChangeCallbacks(result);
            return Optional.of(result);
        } else {
        	return Optional.empty();
        }
    }
    
    private String[] prepareArgsAsArray(Object[] fieldsAndValues) {
        String[] args = new String[fieldsAndValues.length];
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("A value must be specified for each field name");
        }
        for (int i=0; i<args.length; i++) {
            if (i % 2 == 0) {
                // Process field
                if (fieldsAndValues[i] instanceof String) {
                    args[i] = (String) fieldsAndValues[i];
                } else {
                    throw new IllegalArgumentException("Field name is set operation must be a String");
                }
            } else {
                // Process value
                args[i] = formatter.serialize(fieldsAndValues[i]);
            }
        }
        return args;
    }
    
    @Override
    public int hashCode() {
    	return Objects.hash(setKey, id);
    }

    @Override
    public boolean equals(Object obj) {
    	return this.hashCode() == obj.hashCode();
    }
    
    @Override
    public String toString() {
    	return this.hashKey;
    }
}
