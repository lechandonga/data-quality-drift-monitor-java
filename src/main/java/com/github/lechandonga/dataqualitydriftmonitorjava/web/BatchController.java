package com.github.lechandonga.dataqualitydriftmonitorjava.web;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.SubmitResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.SubmitRequest;
import com.github.lechandonga.dataqualitydriftmonitorjava.store.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次提交与历史结论查询。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;
    private final Store store;
    private final ObjectMapper objectMapper;

    public BatchController(BatchService batchService, Store store, ObjectMapper objectMapper) {
        this.batchService = batchService;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{dataset}/submit")
    public Map<String, Object> submit(@PathVariable String dataset, @RequestBody SubmitRequest request) {
        SubmitRequest effective = new SubmitRequest(
                dataset,
                request.format(),
                request.encoding(),
                request.contentBase64(),
                request.content(),
                request.rulesVersion(),
                request.inlineContract(),
                request.inlineRules()
        );
        SubmitResult result = batchService.submit(effective);
        Map<String, Object> body = objectMapperConvert(result.verdict());
        body.put("duplicate", result.duplicate());
        return body;
    }

    private Map<String, Object> objectMapperConvert(BatchVerdict verdict) {
        return objectMapper.convertValue(verdict,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                });
    }

    @GetMapping("/{dataset}")
    public List<BatchVerdict> list(@PathVariable String dataset) {
        return store.listVerdicts(dataset);
    }

    @GetMapping("/{dataset}/{batchId}")
    public ResponseEntity<BatchVerdict> get(@PathVariable String dataset,
                                            @PathVariable String batchId) {
        BatchVerdict verdict = store.findVerdict(dataset, batchId);
        return verdict == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(verdict);
    }
}
