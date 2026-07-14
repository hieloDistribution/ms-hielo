package com.sales.order.controller.dto;

import com.sales.order.model.Client;

import java.math.BigDecimal;
import java.util.UUID;

public record ClientResponse(
        UUID id,
        String name,
        String tax_id,
        String address,
        String phone,
        String email,
        BigDecimal latitude,
        BigDecimal longitude,
        Boolean active
) {
    public static ClientResponse from(Client c) {
        return new ClientResponse(
                c.getId(),
                c.getName(),
                c.getTaxId(),
                c.getAddress(),
                c.getPhone(),
                c.getEmail(),
                c.getLatitude(),
                c.getLongitude(),
                c.getActive()
        );
    }
}