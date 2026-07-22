package com.sales.sync.auth.admin;

import com.sales.sync.auth.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Public invite redemption endpoint. NOT gated by the admin role
 * filter; the user calling this endpoint is anonymous (the invitee
 * is on a phone scanning a link). Rate-limited per IP.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
@RestController
@RequestMapping("/api/v1/auth/admin")
public class AdminInviteRedeemController {

    private final AdminService adminService;
    private final InviteRateLimiter rateLimiter;

    public AdminInviteRedeemController(AdminService adminService,
                                       InviteRateLimiter rateLimiter) {
        this.adminService = adminService;
        this.rateLimiter = rateLimiter;
    }

    public record RedeemRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Size(max = 255) String fullName
    ) {}

    public record RedeemResponse(
            String userId,
            String email,
            java.util.Set<String> roles
    ) {}

    @PostMapping("/invites/redeem")
    public ResponseEntity<RedeemResponse> redeem(@RequestBody @Valid RedeemRequest body,
                                                 HttpServletRequest request) {
        String ip = clientIp(request);
        if (!rateLimiter.tryAcquire(ip)) {
            long retryAfter = rateLimiter.timeUntilRefill(ip).toSeconds();
            throw new AdminException.RateLimited(Math.max(1, retryAfter));
        }
        User u = adminService.redeemInvite(body.token(), body.password(), body.fullName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RedeemResponse(
                        u.getId().toString(),
                        u.getEmail(),
                        u.getRoles().stream()
                                .map(r -> r.getName())
                                .collect(Collectors.toSet())));
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
