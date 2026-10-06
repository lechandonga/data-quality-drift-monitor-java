package com.github.lechandonga.dataqualitydriftmonitorjava.contract;

public record FieldDefinition(String name, DataType type, boolean required) {
}
