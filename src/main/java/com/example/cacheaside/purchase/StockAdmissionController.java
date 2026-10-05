package com.example.cacheaside.purchase;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StockAdmissionController {
    private final StockAdmissionService admission;

    public StockAdmissionController(StockAdmissionService admission) {
        this.admission = admission;
    }

    @GetMapping("/demo/stock/{id}")
    public StockAdmissionService.Status inspect(@PathVariable long id) {
        return admission.inspect(id);
    }

    @PostMapping("/demo/stock/{id}/reconcile")
    public StockAdmissionService.Status reconcile(@PathVariable long id) {
        return admission.reconcile(id);
    }
}
