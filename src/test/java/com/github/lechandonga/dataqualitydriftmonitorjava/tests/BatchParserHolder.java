package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.contract.Contract;
import org.springframework.stereotype.Component;

@Component
public class BatchParserHolder {

    private final Contract contract;

    public BatchParserHolder(Contract contract) {
        this.contract = contract;
    }

    public Contract contract() {
        return contract;
    }
}
