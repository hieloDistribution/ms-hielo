package com.sales.sync.controller;

import com.sales.sync.model.MutationDto;
import com.sales.sync.model.ProcessedRequest;
import com.sales.sync.model.SyncResponseDto;
import com.sales.sync.repository.ProcessedRequestRepository;
import com.sales.sync.service.SyncService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncService syncService;
    private final ProcessedRequestRepository processedRequestRepository;

    public SyncController(SyncService syncService, ProcessedRequestRepository processedRequestRepository) {
        this.syncService = syncService;
        this.processedRequestRepository = processedRequestRepository;
    }

    @PostMapping
    public ResponseEntity<SyncResponseDto> syncMutations(@RequestBody @Valid List<MutationDto> mutations) {
        if (mutations == null || mutations.isEmpty()) {
            return ResponseEntity.badRequest().body(new SyncResponseDto(false, List.of(), "El lote de mutaciones esta vacio"));
        }
        SyncResponseDto response = syncService.processSync(mutations);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/status/{clientRequestId}")
    public ResponseEntity<ProcessedRequest> getSyncStatus(@PathVariable String clientRequestId) {
        return processedRequestRepository.findById(clientRequestId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
