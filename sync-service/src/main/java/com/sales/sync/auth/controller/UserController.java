package com.sales.sync.auth.controller;

import com.sales.sync.auth.dto.LocationUpdateRequest;
import com.sales.sync.auth.dto.UpdateProfileRequest;
import com.sales.sync.auth.dto.UserResponse;
import com.sales.sync.auth.security.AuthContext;
import com.sales.sync.auth.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserProfileService userProfileService;
    private final AuthContext authContext;

    public UserController(UserProfileService userProfileService, AuthContext authContext) {
        this.userProfileService = userProfileService;
        this.authContext = authContext;
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe() {
        return ResponseEntity.ok(userProfileService.getMe(authContext.requireUserId()));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateMe(@Valid @RequestBody UpdateProfileRequest body) {
        return ResponseEntity.ok(userProfileService.updateMe(authContext.requireUserId(), body));
    }

    @PatchMapping("/me/location")
    public ResponseEntity<Void> updateMyLocation(@Valid @RequestBody LocationUpdateRequest body) {
        userProfileService.updateLocation(authContext.requireUserId(), body);
        return ResponseEntity.noContent().build();
    }
}