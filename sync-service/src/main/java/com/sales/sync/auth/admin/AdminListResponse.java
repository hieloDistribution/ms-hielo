package com.sales.sync.auth.admin;

import java.util.List;

/**
 * Generic paginated list response. Owner: change {@code admin-console}
 * PR4.
 */
public record AdminListResponse<T>(List<T> items, long total, int page, int pageSize) {}
