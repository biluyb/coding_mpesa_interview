package com.bililign.mpesaWallet.controller;

import com.bililign.mpesaWallet.dto.BankCallbackRequest;
import com.bililign.mpesaWallet.service.PayoutService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mpesa/v1/callbacks/bank")
public class BankCallbackController {

    private final PayoutService payoutService;

    public BankCallbackController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @PostMapping
    public ResponseEntity<Void> receiveCallback(@Valid @RequestBody BankCallbackRequest request) {
        if ("SUCCEEDED".equalsIgnoreCase(request.getStatus())) {
            payoutService.finalizeSuccess(request.getPayout_id(), request.getBank_ref());
        } else {
            payoutService.finalizeFailed(request.getPayout_id());
        }
        return ResponseEntity.ok().build();
    }
}
