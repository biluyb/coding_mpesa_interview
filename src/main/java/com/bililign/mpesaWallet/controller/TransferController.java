package com.bililign.mpesaWallet.controller;

import com.bililign.mpesaWallet.dto.ReversalRequest;
import com.bililign.mpesaWallet.dto.ReversalResponse;
import com.bililign.mpesaWallet.dto.TransferRequest;
import com.bililign.mpesaWallet.dto.TransferResponse;
import com.bililign.mpesaWallet.service.IdempotencyService;
import com.bililign.mpesaWallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mpesa/v1/p2p/transfers")
public class TransferController {

    private final TransferService transferService;
    private final IdempotencyService idempotencyService;

    public TransferController(TransferService transferService, IdempotencyService idempotencyService) {
        this.transferService = transferService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<?> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        return idempotencyService.execute(
                idempotencyKey,
                "/mpesa/v1/p2p/transfers",
                request,
                () -> {
                    TransferResponse response = transferService.executeTransfer(request, idempotencyKey);
                    return ResponseEntity.status(HttpStatus.CREATED).body(response);
                }
        );
    }

    @PostMapping("/{id}/reversal")
    public ResponseEntity<ReversalResponse> reverse(
            @PathVariable("id") String transferId,
            @RequestBody(required = false) ReversalRequest request) {
        ReversalResponse response = transferService.executeReversal(transferId, request);
        return ResponseEntity.ok(response);
    }
}
