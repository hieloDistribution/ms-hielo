package com.sales.order.repository;

import com.sales.order.model.Vendor;
import com.sales.order.sync.RemoteUser;
import com.sales.order.sync.SyncAuthClient;
import com.sales.order.sync.exceptions.UnknownUserException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit test for {@link VendorRepositoryImpl}. Asserts the
 * forward-direction integrity invariant fires BEFORE the JPA {@code persist}
 * / {@code merge} call (design §3.1 forward direction).
 *
 * <p>Spring's @DataJpaTest slice is avoided here because we want strict
 * ordering control over the {@link SyncAuthClient} stub and the
 * {@link EntityManager} mock — not Hibernate behaviour.
 */
@ExtendWith(MockitoExtension.class)
class VendorRepositoryImplTest {

    @Mock
    private EntityManager em;

    @Mock
    private SyncAuthClient syncAuthClient;

    private VendorRepositoryImpl impl;

    @BeforeEach
    void setUp() {
        impl = new VendorRepositoryImpl(em, syncAuthClient);
    }

    @Test
    void save_withUnknownUserId_throwsUnknownUserException() {
        UUID userId = UUID.randomUUID();
        when(syncAuthClient.getUserById(userId)).thenReturn(Optional.empty());

        Vendor v = validVendor(userId);

        assertThatThrownBy(() -> impl.save(v))
                .isInstanceOf(UnknownUserException.class)
                .hasMessageContaining(userId.toString());
        verify(em, never()).persist(any());
        verify(em, never()).merge(any());
    }

    @Test
    void save_withKnownUserId_persists() {
        UUID userId = UUID.randomUUID();
        RemoteUser remote = new RemoteUser(userId, "user@example.com", false, null);
        when(syncAuthClient.getUserById(userId)).thenReturn(Optional.of(remote));

        Vendor v = validVendor(userId);
        Vendor persisted = impl.save(v);

        assertThat(persisted).isSameAs(v);
        verify(em, times(1)).persist(v);
        verify(em, never()).merge(any());
    }

    @Test
    void save_withExistingId_mergesAfterIntegrityCheck() {
        UUID userId = UUID.randomUUID();
        RemoteUser remote = new RemoteUser(userId, "user@example.com", false, null);
        when(syncAuthClient.getUserById(userId)).thenReturn(Optional.of(remote));

        Vendor v = validVendor(userId);
        v.setId(UUID.randomUUID()); // emulate an update — id present
        when(em.merge(v)).thenReturn(v);

        Vendor merged = impl.save(v);

        assertThat(merged).isSameAs(v);
        verify(em, times(1)).merge(v);
        verify(em, never()).persist(any());
    }

    @Test
    void save_callsSyncAuthClientExactlyOnce() {
        UUID userId = UUID.randomUUID();
        when(syncAuthClient.getUserById(userId))
                .thenReturn(Optional.of(new RemoteUser(userId, "u@e", false, null)));

        Vendor v = validVendor(userId);
        impl.save(v);

        verify(syncAuthClient, times(1)).getUserById(userId);
    }

    private Vendor validVendor(UUID userId) {
        Vendor v = new Vendor();
        v.setUserId(userId);
        v.setDisplayName("Vendedor Demo");
        return v;
    }
}