package com.bililign.mpesaWallet.controller;

import com.bililign.mpesaWallet.dto.PayoutCreateResponse;
import com.bililign.mpesaWallet.dto.PayoutDetailsResponse;
import com.bililign.mpesaWallet.dto.PayoutRequest;
import com.bililign.mpesaWallet.service.IdempotencyService;
import com.bililign.mpesaWallet.service.PayoutService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mpesa/v1/payouts")
public class PayoutController {

    private final PayoutService payoutService;
    private final IdempotencyService idempotencyService;

    public PayoutController(PayoutService payoutService, IdempotencyService idempotencyService) {
        this.payoutService = payoutService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<?> createPayout(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutRequest request) {

        return idempotencyService.execute(
                idempotencyKey,
                "/mpesa/v1/payouts",
                request,
                () -> {
                    PayoutCreateResponse response = payoutService.createPayout(request, idempotencyKey);
                    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
                }
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayoutDetailsResponse> getPayout(@PathVariable("id") String id) {
        return ResponseEntity.ok(payoutService.getPayout(id));
    }
}
