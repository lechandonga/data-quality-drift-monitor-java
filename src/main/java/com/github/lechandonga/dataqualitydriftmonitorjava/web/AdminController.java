package com.github.lechandonga.dataqualitydriftmonitorjava.web;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.Baseline;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.QualityRules;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.AdminService;
import com.github.lechandonga.dataqualitydriftmonitorjava.store.Store;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 契约、规则版本与漂移基线的本地管理接口。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final Store store;

    public AdminController(AdminService adminService, Store store) {
        this.adminService = adminService;
        this.store = store;
    }

    @PostMapping("/contracts")
    public Contract registerContract(@RequestBody Contract contract) {
        return adminService.registerContract(contract);
    }

    @GetMapping("/contracts")
    public Map<String, Contract> contracts() {
        return store.allContracts();
    }

    @PostMapping("/datasets/{dataset}/rules")
    public QualityRules registerRules(@PathVariable String dataset, @RequestBody QualityRules rules) {
        return adminService.registerRules(dataset, rules);
    }

    @GetMapping("/datasets/{dataset}/rules")
    public Map<String, QualityRules> listRules(@PathVariable String dataset) {
        return adminService.listRuleVersions(dataset);
    }

    @GetMapping("/datasets/{dataset}/baseline")
    public Baseline getBaseline(@PathVariable String dataset) {
        return adminService.getBaseline(dataset);
    }

    @PutMapping("/datasets/{dataset}/baseline")
    public Baseline updateBaseline(@PathVariable String dataset, @RequestBody Baseline patch) {
        return adminService.updateBaselineConfig(dataset, patch);
    }

    @PostMapping("/datasets/{dataset}/baseline/rebuild")
    public Baseline rebuildBaseline(@PathVariable String dataset) {
        return adminService.rebuildBaseline(dataset);
    }
}
