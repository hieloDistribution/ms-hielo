package com.sales.sync.auth.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.admin.AdminAuditLogRepository;
import com.sales.sync.auth.admin.AdminInviteRepository;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.repository.RoleRepository;
import com.sales.sync.auth.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BDD-style integration tests for the {@code /api/v1/admin/**} surface
 * and the public {@code /api/v1/auth/admin/invites/redeem} endpoint.
 *
 * <p>Each test:
 * <ol>
 *   <li>Seeds users + roles + invites directly via the repository
 *       layer (so we control the starting state).</li>
 *   <li>Logs in via the real {@code /api/v1/auth/login} endpoint to
 *       obtain an access token (so the JWT is signed by the real
 *       JwtService and parsed by the real JwtAuthenticationFilter).</li>
 *   <li>Issues HTTP requests to the admin endpoints through
 *       {@code TestRestTemplate} (so the request flows through the real
 *       Spring filter chain: AdminRoleGateFilter + the controller +
 *       AdminService + the real H2 database via Hibernate
 *       ddl-auto=create-drop + RolesSeeder).</li>
 *   <li>Asserts the response status, body, and side effects (audit log
 *       rows persisted, refresh tokens revoked, etc.).</li>
 * </ol>
 *
 * <p>The {@code application-test.yml} profile uses H2 with
 * {@code ddl-auto=create-drop}, so Hibernate generates the schema
 * (including the new {@code admin_invites} and {@code admin_audit_log}
 * tables from PR4's entities). Flyway is disabled; we rely on Hibernate.
 *
 * <p>Owner: change {@code admin-console} PR4 (BDD coverage).
 */
@SpringBootTest(
        classes = com.sales.sync.SyncServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class AdminControllerIT extends AbstractPostgresIT {

    @Autowired
    void configureHttp(TestRestTemplate httpTemplate) {
        var apache = new HttpComponentsClientHttpRequestFactory();
        var buffered = new BufferingClientHttpRequestFactory(apache);
        httpTemplate.getRestTemplate().setRequestFactory(buffered);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired RoleRepository roles;
    @Autowired AdminInviteRepository invites;
    @Autowired AdminAuditLogRepository auditLogs;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestRestTemplate http;

    private static final String PWD = "test-password-1234";

    @BeforeEach
    void setUp() {
        tokens.deleteAll();
        auditLogs.deleteAll();
        invites.deleteAll();
        users.deleteAll();
    }

    @AfterEach
    void clean() {
        tokens.deleteAll();
        auditLogs.deleteAll();
        invites.deleteAll();
        users.deleteAll();
    }

    // ----- helpers -----

    private User seedUser(String email, User.Role role, boolean active, boolean mustChangePwd) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(PWD));
        u.setLocked(false);
        u.setActive(active);
        u.setMustChangePassword(mustChangePwd);
        u.setRoles(Set.of(roles.findByName(role.name()).orElseThrow()));
        return users.saveAndFlush(u);
    }

    /** Login via the real endpoint. Returns the access token string. */
    private String login(String email) {
        ResponseEntity<JsonNode> resp = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/login",
                Map.of("email", email, "password", PWD),
                JsonNode.class);
        assertThat(resp.getStatusCode()).as("login %s", email).isEqualTo(HttpStatus.OK);
        return resp.getBody().get("access_token").asText();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private <T> ResponseEntity<JsonNode> get(String path, String token) {
        return http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(bearer(token)), JsonNode.class);
    }

    private <T> ResponseEntity<JsonNode> post(String path, String token, Object body) {
        return http.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(objectMapper.convertValue(body, Map.class), bearer(token)),
                JsonNode.class);
    }

    private <T> ResponseEntity<JsonNode> patch(String path, String token, Object body) {
        return http.exchange(url(path), HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.convertValue(body, Map.class), bearer(token)),
                JsonNode.class);
    }

    private <T> ResponseEntity<JsonNode> postNoAuth(String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(objectMapper.convertValue(body, Map.class), h), JsonNode.class);
    }

    // ============================================================
    // /api/v1/admin/users (list)
    // ============================================================

    @Nested
    @DisplayName("GET /api/v1/admin/users")
    class ListUsers {
        @Test
        @DisplayName("returns 403 (admin_role_required) without bearer token — the gate filter intercepts BEFORE the security entry point")
        void no_token_returns_403() {
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/users", null);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(resp.getBody().get("error").asText()).isEqualTo("admin_role_required");
        }

        @Test
        @DisplayName("returns 403 for a cliente caller (not admin)")
        void non_admin_caller_returns_403() {
            seedUser("cliente@hielo.com", User.Role.cliente, true, false);
            String token = login("cliente@hielo.com");
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/users", token);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(resp.getBody().get("error").asText()).isEqualTo("admin_role_required");
        }

        @Test
        @DisplayName("returns 200 with all users for an admin caller")
        void admin_lists_all_users() {
            seedUser("admin@hielo.com", User.Role.admin, true, false);
            seedUser("repartidor@hielo.com", User.Role.repartidor, true, false);
            seedUser("cliente@hielo.com", User.Role.cliente, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/users", token);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("total").asInt()).isEqualTo(3);
            assertThat(resp.getBody().get("items")).hasSize(3);
        }

        @Test
        @DisplayName("filters by role=cliente")
        void admin_filters_by_role() {
            seedUser("admin@hielo.com", User.Role.admin, true, false);
            seedUser("repartidor@hielo.com", User.Role.repartidor, true, false);
            seedUser("cliente@hielo.com", User.Role.cliente, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/users?role=cliente", token);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("total").asInt()).isEqualTo(1);
            assertThat(resp.getBody().get("items").get(0).get("email").asText()).isEqualTo("cliente@hielo.com");
        }

        @Test
        @DisplayName("searches by q substring (case-insensitive)")
        void admin_searches_by_substring() {
            seedUser("admin@hielo.com", User.Role.admin, true, false);
            seedUser("cliente1@hielo.com", User.Role.cliente, true, false);
            seedUser("cliente2@hielo.com", User.Role.cliente, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/users?q=CLIENTE", token);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("total").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("returns deactivated users with active=false marker")
        void deactivated_users_visible_with_marker() {
            seedUser("admin@hielo.com", User.Role.admin, true, false);
            User suspended = seedUser("suspended@hielo.com", User.Role.cliente, false, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/users", token);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode suspendedItem = null;
            for (JsonNode item : resp.getBody().get("items")) {
                if (item.get("email").asText().equals("suspended@hielo.com")) {
                    suspendedItem = item;
                }
            }
            assertThat(suspendedItem).isNotNull();
            assertThat(suspendedItem.get("active").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("paginates with page and page_size")
        void admin_paginates() {
            seedUser("admin@hielo.com", User.Role.admin, true, false);
            for (int i = 0; i < 5; i++) {
                seedUser("c" + i + "@hielo.com", User.Role.cliente, true, false);
            }
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/users?page=1&page_size=3", token);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("items")).hasSize(3);
            assertThat(resp.getBody().get("total").asInt()).isEqualTo(6);
        }
    }

    // ============================================================
    // PATCH /api/v1/admin/users/{id}/roles
    // ============================================================

    @Nested
    @DisplayName("PATCH /api/v1/admin/users/{id}/roles")
    class ChangeRoles {
        @Test
        @DisplayName("admin promotes a repartidor to admin and writes audit row")
        void admin_promotes() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            User target = seedUser("target@hielo.com", User.Role.repartidor, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = patch("/api/v1/admin/users/" + target.getId() + "/roles",
                    token, Map.of("roles", List.of("admin")));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("roles").get(0).asText()).isEqualTo("admin");
            assertThat(auditLogs.count()).isEqualTo(1);
            assertThat(auditLogs.findAll().get(0).getAction()).isEqualTo("roles_changed");
        }

        @Test
        @DisplayName("self-demote of last admin -> 409 cannot_self_demote_last_admin")
        void self_demote_last_admin_blocked() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = patch("/api/v1/admin/users/" + admin.getId() + "/roles",
                    token, Map.of("roles", List.of("cliente")));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(resp.getBody().get("error").asText()).isEqualTo("cannot_self_demote_last_admin");
        }

        @Test
        @DisplayName("admin A can demote admin B to cliente; B is no longer admin; A is still the only one with admin role and is now locked from self-demote")
        void admin_can_demote_another_admin_then_self_locked() {
            User adminA = seedUser("a@hielo.com", User.Role.admin, true, false);
            User adminB = seedUser("b@hielo.com", User.Role.admin, true, false);
            String tokenA = login("a@hielo.com");

            // 1. A demotes B to cliente -> 200 OK.
            ResponseEntity<JsonNode> demoteB = patch(
                    "/api/v1/admin/users/" + adminB.getId() + "/roles",
                    tokenA, Map.of("roles", List.of("cliente")));
            assertThat(demoteB.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(users.findById(adminB.getId()).orElseThrow().getRoles())
                    .as("B's roles are now {cliente}")
                    .extracting(r -> r.getName())
                    .containsExactly("cliente");

            // 2. A is the only active admin. A cannot self-demote.
            ResponseEntity<JsonNode> selfDemote = patch(
                    "/api/v1/admin/users/" + adminA.getId() + "/roles",
                    tokenA, Map.of("roles", List.of("cliente")));
            assertThat(selfDemote.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(selfDemote.getBody().get("error").asText())
                    .isEqualTo("cannot_self_demote_last_admin");
        }

        @DisplayName("self-demote of one of two admins -> allowed")
        void self_demote_when_other_admins_exist() {
            User admin1 = seedUser("admin1@hielo.com", User.Role.admin, true, false);
            User admin2 = seedUser("admin2@hielo.com", User.Role.admin, true, false);
            String token = login("admin1@hielo.com");
            ResponseEntity<JsonNode> resp = patch("/api/v1/admin/users/" + admin1.getId() + "/roles",
                    token, Map.of("roles", List.of("cliente")));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("unknown role name -> 400 invalid_role")
        void unknown_role_rejected() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            User target = seedUser("target@hielo.com", User.Role.repartidor, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = patch("/api/v1/admin/users/" + target.getId() + "/roles",
                    token, Map.of("roles", List.of("superhero")));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().get("error").asText()).isEqualTo("invalid_role");
        }

        @Test
        @DisplayName("unknown user id -> 404 user_not_found")
        void unknown_user_returns_404() {
            seedUser("admin@hielo.com", User.Role.admin, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = patch("/api/v1/admin/users/" + UUID.randomUUID() + "/roles",
                    token, Map.of("roles", List.of("admin")));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(resp.getBody().get("error").asText()).isEqualTo("user_not_found");
        }

        @Test
        @DisplayName("deactivated admin caller is rejected by the gate (DB re-query)")
        void deactivated_admin_caller_rejected() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            User target = seedUser("target@hielo.com", User.Role.repartidor, true, false);
            String token = login("admin@hielo.com");
            // After login, deactivate the admin.
            admin.setActive(false);
            admin.setLocked(true);
            users.save(admin);
            ResponseEntity<JsonNode> resp = patch("/api/v1/admin/users/" + target.getId() + "/roles",
                    token, Map.of("roles", List.of("admin")));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ============================================================
    // POST /api/v1/admin/users/{id}/deactivate
    // ============================================================

    @Nested
    @DisplayName("POST /api/v1/admin/users/{id}/deactivate")
    class Deactivate {
        @Test
        @DisplayName("deactivates a target user and writes audit row")
        void deactivates_target() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            User target = seedUser("target@hielo.com", User.Role.repartidor, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = post("/api/v1/admin/users/" + target.getId() + "/deactivate",
                    token, Map.of());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("active").asBoolean()).isFalse();
            User reloaded = users.findById(target.getId()).orElseThrow();
            assertThat(reloaded.isActive()).isFalse();
            assertThat(reloaded.isLocked()).isTrue();
            assertThat(auditLogs.count()).isEqualTo(1);
            assertThat(auditLogs.findAll().get(0).getAction()).isEqualTo("user_deactivated");
        }

        @Test
        @DisplayName("is idempotent (deactivating an already-deactivated user returns 200)")
        void idempotent() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            User target = seedUser("target@hielo.com", User.Role.cliente, false, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = post("/api/v1/admin/users/" + target.getId() + "/deactivate",
                    token, Map.of());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("admin A can deactivate admin B; then A is the last admin and is locked from self-deactivate until B is reactivated")
        void admin_can_deactivate_another_admin_then_self_locked_until_reactivate() {
            User adminA = seedUser("a@hielo.com", User.Role.admin, true, false);
            User adminB = seedUser("b@hielo.com", User.Role.admin, true, false);
            String tokenA = login("a@hielo.com");

            // 1. A deactivates B -> 200 OK, B is inactive.
            ResponseEntity<JsonNode> deactivateB = post(
                    "/api/v1/admin/users/" + adminB.getId() + "/deactivate", tokenA, Map.of());
            assertThat(deactivateB.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(users.findById(adminB.getId()).orElseThrow().isActive()).isFalse();

            // 2. Now A is the only active admin. A cannot self-deactivate.
            ResponseEntity<JsonNode> selfDeactivate = post(
                    "/api/v1/admin/users/" + adminA.getId() + "/deactivate", tokenA, Map.of());
            assertThat(selfDeactivate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(selfDeactivate.getBody().get("error").asText())
                    .isEqualTo("cannot_deactivate_last_admin");
            assertThat(users.findById(adminA.getId()).orElseThrow().isActive())
                    .as("A is still active after the blocked self-deactivate")
                    .isTrue();

            // 3. A reactivates B -> 200 OK.
            ResponseEntity<JsonNode> reactivateB = post(
                    "/api/v1/admin/users/" + adminB.getId() + "/reactivate", tokenA, Map.of());
            assertThat(reactivateB.getStatusCode()).isEqualTo(HttpStatus.OK);

            // 4. Now A is NOT the last admin. A can self-deactivate.
            ResponseEntity<JsonNode> selfDeactivate2 = post(
                    "/api/v1/admin/users/" + adminA.getId() + "/deactivate", tokenA, Map.of());
            assertThat(selfDeactivate2.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @DisplayName("self-deactivate of last admin -> 409 cannot_deactivate_last_admin")
        void self_deactivate_last_admin_blocked() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = post("/api/v1/admin/users/" + admin.getId() + "/deactivate",
                    token, Map.of());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(resp.getBody().get("error").asText()).isEqualTo("cannot_deactivate_last_admin");
        }
    }

    // ============================================================
    // POST /api/v1/admin/users/{id}/reactivate
    // ============================================================

    @Nested
    @DisplayName("POST /api/v1/admin/users/{id}/reactivate")
    class Reactivate {
        @Test
        @DisplayName("reactivates a target and sets must_change_password=true")
        void reactivates() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            User target = seedUser("target@hielo.com", User.Role.repartidor, false, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = post("/api/v1/admin/users/" + target.getId() + "/reactivate",
                    token, Map.of());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("active").asBoolean()).isTrue();
            assertThat(resp.getBody().get("must_change_password").asBoolean()).isTrue();
        }
    }

    // ============================================================
    // POST /api/v1/admin/invites
    // ============================================================

    @Nested
    @DisplayName("POST /api/v1/admin/invites")
    class IssueInvite {
        @Test
        @DisplayName("issues an invite and returns the cleartext token once")
        void issues_invite() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = post("/api/v1/admin/invites", token,
                    Map.of("email", "new@hielo.com", "role", "repartidor"));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().get("token").asText()).isNotEmpty();
            assertThat(resp.getBody().get("invite_id").asText()).isNotEmpty();
            assertThat(invites.count()).isEqualTo(1);
            assertThat(auditLogs.count()).isEqualTo(1);
            assertThat(auditLogs.findAll().get(0).getAction()).isEqualTo("invite_issued");
        }

        @Test
        @DisplayName("rejects invite for an already-active email -> 409")
        void rejects_existing_email() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            seedUser("taken@hielo.com", User.Role.cliente, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = post("/api/v1/admin/invites", token,
                    Map.of("email", "taken@hielo.com", "role", "admin"));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(resp.getBody().get("error").asText()).isEqualTo("email_already_active");
        }

        @Test
        @DisplayName("rejects invite with cliente role -> 400 invalid_role")
        void rejects_cliente_role() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = post("/api/v1/admin/invites", token,
                    Map.of("email", "new@hielo.com", "role", "cliente"));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ============================================================
    // POST /api/v1/auth/admin/invites/redeem (public, rate-limited)
    // ============================================================

    @Nested
    @DisplayName("POST /api/v1/auth/admin/invites/redeem")
    class RedeemInvite {
        private String issueInvite(String token, String email, String role) {
            ResponseEntity<JsonNode> resp = post("/api/v1/admin/invites", token,
                    Map.of("email", email, "role", role));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            return resp.getBody().get("token").asText();
        }

        @Test
        @DisplayName("redeems a valid token and creates a user with must_change_password=false")
        void redeems() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String adminToken = login("admin@hielo.com");
            String invite = issueInvite(adminToken, "newbie@hielo.com", "repartidor");
            ResponseEntity<JsonNode> resp = postNoAuth("/api/v1/auth/admin/invites/redeem",
                    Map.of("token", invite, "password", "newbie-password-1234", "fullName", "Newbie"));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().get("email").asText()).isEqualTo("newbie@hielo.com");
            User created = users.findByEmail("newbie@hielo.com").orElseThrow();
            assertThat(created.isMustChangePassword()).isFalse();
            assertThat(created.isActive()).isTrue();
            assertThat(created.getRoles()).extracting(r -> r.getName())
                    .containsExactly("repartidor");
        }

        @Test
        @DisplayName("rejects weak password (under 12 chars) -> 422")
        void rejects_weak_password() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String adminToken = login("admin@hielo.com");
            String invite = issueInvite(adminToken, "weakie@hielo.com", "repartidor");
            ResponseEntity<JsonNode> resp = postNoAuth("/api/v1/auth/admin/invites/redeem",
                    Map.of("token", invite, "password", "short", "fullName", "X"));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(resp.getBody().get("error").asText()).isEqualTo("weak_password");
        }

        @Test
        @DisplayName("rejects already-used invite -> 410 invite_already_used")
        void rejects_already_used() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String adminToken = login("admin@hielo.com");
            String invite = issueInvite(adminToken, "reuse@hielo.com", "repartidor");
            // First redemption succeeds
            postNoAuth("/api/v1/auth/admin/invites/redeem",
                    Map.of("token", invite, "password", "newbie-password-1234", "fullName", "X"));
            // Second attempt fails
            ResponseEntity<JsonNode> resp = postNoAuth("/api/v1/auth/admin/invites/redeem",
                    Map.of("token", invite, "password", "newbie-password-1234", "fullName", "X"));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(resp.getBody().get("error").asText()).isEqualTo("invite_already_used");
        }

        @Test
        @DisplayName("rejects tampered token -> 410 invite_bad_signature")
        void rejects_tampered() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String adminToken = login("admin@hielo.com");
            String invite = issueInvite(adminToken, "tamper@hielo.com", "repartidor");
            // Tamper: replace the last char of the signature segment
            String tampered = invite.substring(0, invite.length() - 1)
                    + (invite.charAt(invite.length() - 1) == 'A' ? 'B' : 'A');
            ResponseEntity<JsonNode> resp = postNoAuth("/api/v1/auth/admin/invites/redeem",
                    Map.of("token", tampered, "password", "good-password-1234", "fullName", "X"));
            // Bad signature or malformed; both are 410.
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
        }

        @Test
        @DisplayName("rate-limits after 5 attempts in a row from the same IP -> 429")
        void rate_limited() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String adminToken = login("admin@hielo.com");
            String realInvite = issueInvite(adminToken, "rl-real@hielo.com", "repartidor");
            // Use a non-existent invite (would 410) 5 times in a row from the
            // same IP. The 6th call must be 429.
            for (int i = 0; i < 5; i++) {
                postNoAuth("/api/v1/auth/admin/invites/redeem",
                        Map.of("token", "fake." + i, "password", "good-password-1234"));
            }
            ResponseEntity<JsonNode> resp = postNoAuth("/api/v1/auth/admin/invites/redeem",
                    Map.of("token", realInvite, "password", "good-password-1234"));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(resp.getHeaders().getFirst("Retry-After")).isNotNull();
        }
    }

    // ============================================================
    // GET /api/v1/admin/audit-log
    // ============================================================

    @Nested
    @DisplayName("GET /api/v1/admin/audit-log")
    class AuditLog {
        @Test
        @DisplayName("returns empty list when no audit rows")
        void empty() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            String token = login("admin@hielo.com");
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/audit-log", token);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("total").asInt()).isEqualTo(0);
        }

        @Test
        @DisplayName("returns audit rows after admin actions")
        void returns_audit_rows() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            User target = seedUser("target@hielo.com", User.Role.repartidor, true, false);
            String token = login("admin@hielo.com");
            // Trigger an audit row.
            post("/api/v1/admin/users/" + target.getId() + "/deactivate", token, Map.of());
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/audit-log", token);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("total").asInt()).isEqualTo(1);
            JsonNode row = resp.getBody().get("items").get(0);
            assertThat(row.get("action").asText()).isEqualTo("user_deactivated");
            assertThat(row.get("target_email").asText()).isEqualTo("target@hielo.com");
            assertThat(row.get("request_id").asText()).isNotEmpty();
        }

        @Test
        @DisplayName("filters by action name")
        void filters_by_action() {
            User admin = seedUser("admin@hielo.com", User.Role.admin, true, false);
            User t1 = seedUser("t1@hielo.com", User.Role.repartidor, true, false);
            User t2 = seedUser("t2@hielo.com", User.Role.cliente, true, false);
            String token = login("admin@hielo.com");
            post("/api/v1/admin/users/" + t1.getId() + "/deactivate", token, Map.of());
            post("/api/v1/admin/users/" + t2.getId() + "/deactivate", token, Map.of());
            ResponseEntity<JsonNode> resp = get("/api/v1/admin/audit-log?action=user_deactivated", token);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().get("total").asInt()).isEqualTo(2);
        }
    }
}
