package com.github.lechandonga.dataqualitydriftmonitorjava.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declared field contract. Immutable once built; a version string is carried
 * so that historical batches can be replayed against the contract in effect
 * at submission time.
 */
public class Contract {

    private final String version;
    private final Map<String, FieldDefinition> fields;

    public Contract(String version, List<FieldDefinition> fieldList) {
        this.version = version;
        this.fields = new LinkedHashMap<>();
        for (FieldDefinition f : fieldList) {
            this.fields.put(f.name(), f);
        }
    }

    public String version() {
        return version;
    }

    public FieldDefinition field(String name) {
        return fields.get(name);
    }

    public Set<String> fieldNames() {
        return fields.keySet();
    }

    public List<FieldDefinition> fields() {
        return List.copyOf(fields.values());
    }
}
