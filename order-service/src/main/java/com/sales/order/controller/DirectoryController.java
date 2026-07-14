package com.sales.order.controller;

import com.sales.order.controller.dto.ClientResponse;
import com.sales.order.controller.dto.CreateReviewRequest;
import com.sales.order.controller.dto.DriverResponse;
import com.sales.order.controller.dto.DriverReviewResponse;
import com.sales.order.model.Client;
import com.sales.order.model.DeliveryDriver;
import com.sales.order.model.DriverReview;
import com.sales.order.repository.ClientRepository;
import com.sales.order.repository.DeliveryDriverRepository;
import com.sales.order.repository.DriverReviewRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DirectoryController {

    private final DeliveryDriverRepository drivers;
    private final ClientRepository clients;
    private final DriverReviewRepository reviews;

    public DirectoryController(DeliveryDriverRepository drivers,
                               ClientRepository clients,
                               DriverReviewRepository reviews) {
        this.drivers = drivers;
        this.clients = clients;
        this.reviews = reviews;
    }

    // ---------------- Drivers ----------------

    @GetMapping("/drivers")
    public List<DriverResponse> listActiveDrivers() {
        return drivers.findByActiveTrueOrderByDisplayNameAsc()
                .stream()
                .map(DriverResponse::from)
                .toList();
    }

    @GetMapping("/drivers/me")
    public ResponseEntity<DriverResponse> getMyDriverProfile(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        DeliveryDriver d = drivers.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No driver profile linked to this user"));
        return ResponseEntity.ok(DriverResponse.from(d));
    }

    @PostMapping("/drivers/{driverId}/reviews")
    public ResponseEntity<DriverReviewResponse> createReview(
            @PathVariable UUID driverId,
            @Valid @RequestBody CreateReviewRequest body,
            Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        if (!drivers.existsById(driverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }
        if (reviews.existsByOrderId(body.order_id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order already reviewed");
        }
        DriverReview r = new DriverReview();
        r.setDriverId(driverId);
        r.setOrderId(body.order_id());
        r.setRating(body.rating());
        r.setComment(body.comment());
        r.setCreatedBy(userId);
        reviews.save(r);

        recomputeDriverStars(driverId);

        return ResponseEntity.status(HttpStatus.CREATED).body(DriverReviewResponse.from(r));
    }

    @GetMapping("/drivers/{driverId}/reviews")
    public List<DriverReviewResponse> listReviews(@PathVariable UUID driverId) {
        return reviews.findByDriverIdOrderByCreatedAtDesc(driverId)
                .stream()
                .map(DriverReviewResponse::from)
                .toList();
    }

    private void recomputeDriverStars(UUID driverId) {
        List<DriverReview> all = reviews.findByDriverIdOrderByCreatedAtDesc(driverId);
        if (all.isEmpty()) return;
        double avg = all.stream()
                .mapToInt(r -> r.getRating() == null ? 0 : r.getRating())
                .average()
                .orElse(5.0);
        BigDecimal stars = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);
        DeliveryDriver d = drivers.findById(driverId).orElse(null);
        if (d != null) {
            d.setStars(stars);
            drivers.save(d);
        }
    }

    // ---------------- Clients ----------------

    @GetMapping("/clients")
    public List<ClientResponse> listActiveClients() {
        return clients.findByDeletedAtIsNull()
                .stream()
                .map(ClientResponse::from)
                .toList();
    }

    @GetMapping("/clients/me")
    public ResponseEntity<ClientResponse> getMyClientProfile(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        Client c = clients.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No client profile linked to this user"));
        return ResponseEntity.ok(ClientResponse.from(c));
    }
}