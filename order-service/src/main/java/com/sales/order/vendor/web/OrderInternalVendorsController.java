package com.sales.order.vendor.web;

import com.sales.order.model.Vendor;
import com.sales.order.repository.VendorRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reverse-direction internal endpoint (design §3.1 step 1-4, D-05).
 * Exposes {@code GET /internal/vendors/by-user/{userId}} for
 * {@code sync-service} to consult before any User deletion. Returns
 * {@code {"userId": ..., "hasActiveVendor": true|false}} where "active" means
 * "a non-soft-deleted vendor with {@code active = true} still references the
 * user".
 *
 * <p>Security: gated by {@code InternalSecurityConfig} in
 * {@code com.sales.order.config} — bearer-token-only authentication distinct
 * from the JWT-validated {@code /api/**} chain.
 */
@RestController
public class OrderInternalVendorsController {

    private final VendorRepository vendorRepository;
    private final EntityManager em;

    public OrderInternalVendorsController(VendorRepository vendorRepository, EntityManager em) {
        this.vendorRepository = vendorRepository;
        this.em = em;
    }

    @GetMapping("/internal/vendors/by-user/{userId}")
    public ResponseEntity<HasActiveVendorResponse> hasActiveVendorForUser(@PathVariable UUID userId) {
        long count = vendorRepository.countByUserIdAndDeletedAtIsNullAndActiveTrue(userId);
        return ResponseEntity.ok().body(new HasActiveVendorResponse(userId, count > 0));
    }

    @PostMapping("/internal/vendors")
    @Transactional
    public ResponseEntity<Void> createVendor(@RequestBody InternalVendorDto dto) {
        Vendor v = new Vendor();
        v.setId(dto.id());
        v.setUserId(dto.userId());
        v.setDisplayName(dto.displayName());
        v.setEmail(dto.email());
        v.setPhone(dto.phone());
        v.setActive(true);
        em.persist(v);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}