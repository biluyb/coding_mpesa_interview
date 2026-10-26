package com.bililign.mpesaWallet.controller;

import com.bililign.mpesaWallet.dto.WalletResponse;
import com.bililign.mpesaWallet.dto.WalletStatusRequest;
import com.bililign.mpesaWallet.dto.WalletStatusResponse;
import com.bililign.mpesaWallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mpesa/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable("id") String id) {
        return ResponseEntity.ok(walletService.getWallet(id));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<WalletStatusResponse> updateStatus(
            @PathVariable("id") String id,
            @Valid @RequestBody WalletStatusRequest request) {
        return ResponseEntity.ok(walletService.updateStatus(id, request.getStatus()));
    }
}
