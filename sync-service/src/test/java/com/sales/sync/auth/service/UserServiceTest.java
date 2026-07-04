package com.sales.sync.auth.service;

import com.sales.sync.auth.internal.OrderInternalVendorsClient;
import com.sales.sync.auth.internal.OrderServiceUnavailable;
import com.sales.sync.auth.internal.UserHasActiveVendorException;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit test for {@link UserService#delete} — asserts the
 * reverse-direction cross-DB integrity invariant fires BEFORE the User row
 * is locked/persisted (design §3.1 reverse direction, D-05), and that
 * {@link OrderServiceUnavailable} fails closed.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderInternalVendorsClient orderInternalVendorsClient;

    @InjectMocks
    private UserService userService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    // --- happy path: no active vendor ----------------------------------

    @Test
    void delete_withNoActiveVendor_locksAndPersists() {
        User user = unLockedUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(orderInternalVendorsClient.hasActiveVendorForUser(userId)).thenReturn(false);

        userService.delete(userId);

        verify(userRepository, times(1)).save(user);
        // Locking is the v1 soft-delete proxy
        org.assertj.core.api.Assertions.assertThat(user.isLocked()).isTrue();
    }

    // --- integrity assertion ------------------------------------------

    @Test
    void delete_withActiveVendor_throws_andDoesNotPersist() {
        User user = unLockedUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(orderInternalVendorsClient.hasActiveVendorForUser(userId)).thenReturn(true);

        assertThatThrownBy(() -> userService.delete(userId))
                .isInstanceOf(UserHasActiveVendorException.class)
                .hasMessageContaining(userId.toString());
        verify(userRepository, never()).save(any());
    }

    // --- idempotent: already locked → no probe, no save ----------------

    @Test
    void delete_idempotentWhenAlreadyLocked() {
        User locked = lockedUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(locked));

        userService.delete(userId);

        // Skip the upstream probe entirely; do not re-save
        verify(orderInternalVendorsClient, never()).hasActiveVendorForUser(any());
        verify(userRepository, never()).save(any());
    }

    // --- user not found -----------------------------------------------

    @Test
    void delete_unknownUser_throwsIllegalArgument() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.delete(userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(userId.toString());
        verify(orderInternalVendorsClient, never()).hasActiveVendorForUser(any());
        verify(userRepository, never()).save(any());
    }

    // --- fail-closed on upstream outage -------------------------------

    @Test
    void delete_whenOrderServiceUnavailable_propagatesException() {
        User user = unLockedUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(orderInternalVendorsClient.hasActiveVendorForUser(userId))
                .thenThrow(new OrderServiceUnavailable("timeout"));

        assertThatThrownBy(() -> userService.delete(userId))
                .isInstanceOf(OrderServiceUnavailable.class);
        // Soft-delete MUST NOT persist when integrity cannot be confirmed
        verify(userRepository, never()).save(any());
    }

    // --- helpers -------------------------------------------------------

    private User unLockedUser(UUID id) {
        User u = new User();
        u.setId(id);
        u.setEmail("user@example.com");
        u.setLocked(false);
        return u;
    }

    private User lockedUser(UUID id) {
        User u = unLockedUser(id);
        u.setLocked(true);
        return u;
    }
}