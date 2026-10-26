package com.bililign.mpesaWallet.controller;

import com.bililign.mpesaWallet.dto.StatementResponse;
import com.bililign.mpesaWallet.service.StatementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mpesa/v1/wallets")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @GetMapping("/{id}/statement")
    public ResponseEntity<StatementResponse> getStatement(
            @PathVariable("id") String walletId,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {

        return ResponseEntity.ok(statementService.getStatement(walletId, limit, cursor));
    }
}
