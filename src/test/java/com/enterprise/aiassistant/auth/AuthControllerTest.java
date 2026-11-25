package com.enterprise.aiassistant.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.aiassistant.audit.AuditEvent;
import com.enterprise.aiassistant.audit.AuditLog;
import com.enterprise.aiassistant.audit.AuditLogRepository;
import com.enterprise.aiassistant.user.User;
import com.enterprise.aiassistant.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// BDD 3.1-3.10 integration tests
// H2 fallback is supplied by application-test.yml (test profile); @DynamicPropertySource
// overrides the datasource URL/credentials with the Testcontainer when Docker is available.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthControllerTest {

  private static final String TEST_SECRET = "test-secret-256bit-xxxxxxxxxxxxxxxx";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired AuditLogRepository auditLogRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    if (!POSTGRES.isRunning()) return;
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @BeforeEach
  void setUp() {
    auditLogRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
    // Seed a regular user and an admin for login-related tests
    userRepository.save(
        new User(
            "user@example.com", passwordEncoder.encode("password123"), "ROLE_USER", "IT", true));
    userRepository.save(
        new User(
            "admin@example.com", passwordEncoder.encode("admin123"), "ROLE_ADMIN", "IT", true));
  }

  // ─── BDD 3.1: Register ──────────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.1: register with new email returns 201 and JWT")
  void register_withNewEmail_returns201AndToken() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"email\":\"new@example.com\",\"password\":\"NewPass123\",\"department\":\"Engineering\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(jsonPath("$.token_type").value("Bearer"))
        .andExpect(jsonPath("$.email").value("new@example.com"))
        .andExpect(jsonPath("$.role").value("ROLE_USER"));
    assertTrue(hasAuditEvent(AuditEvent.REGISTER, "new@example.com"));
  }

  // ─── BDD 3.2: Duplicate email ───────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.2: register with existing email returns 409 and no audit log written")
  void register_withExistingEmail_returns409() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"Pass1234\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_EXISTS"));
    assertTrue(
        auditLogRepository.findAll().stream()
            .noneMatch(log -> log.getEvent() == AuditEvent.REGISTER),
        "No REGISTER audit log should be written for duplicate email");
  }

  // ─── BDD 3.3: Login success ─────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.3: login with correct credentials returns 200 and JWT")
  void login_withValidCredentials_returns200AndToken() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"password123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(jsonPath("$.token_type").value("Bearer"))
        .andExpect(jsonPath("$.expires_in").value(3600));
  }

  @Test
  @DisplayName("BDD 3.3: login success writes LOGIN_SUCCESS audit log with ip_address")
  void login_withValidCredentials_writesAuditLog() throws Exception {
    login("user@example.com", "password123");
    assertTrue(hasAuditEvent(AuditEvent.LOGIN_SUCCESS, "user@example.com"));
    assertTrue(
        auditLogRepository.findAll().stream()
            .filter(log -> log.getEvent() == AuditEvent.LOGIN_SUCCESS)
            .anyMatch(log -> log.getIpAddress() != null && !log.getIpAddress().isBlank()),
        "LOGIN_SUCCESS audit log must record ip_address");
  }

  // ─── BDD 3.4: Login failure ─────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.4: login with wrong password returns 401")
  void login_withWrongPassword_returns401() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
  }

  @Test
  @DisplayName("BDD 3.4: login failure writes LOGIN_FAILED audit log with ip_address")
  void login_withWrongPassword_writesAuditLog() throws Exception {
    mockMvc.perform(
        post("/api/auth/login")
            .contentType(APPLICATION_JSON)
            .content("{\"email\":\"user@example.com\",\"password\":\"wrong\"}"));
    assertTrue(hasAuditEvent(AuditEvent.LOGIN_FAILED, "user@example.com"));
    assertTrue(
        auditLogRepository.findAll().stream()
            .filter(log -> log.getEvent() == AuditEvent.LOGIN_FAILED)
            .anyMatch(log -> log.getIpAddress() != null && !log.getIpAddress().isBlank()),
        "LOGIN_FAILED audit log must record ip_address");
  }

  @Test
  @DisplayName("BDD 3.4: unknown email returns same 401 as wrong password (no user enumeration)")
  void login_withUnknownEmail_returnsSameErrorAsWrongPassword() throws Exception {
    MvcResult wrongPassword =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(APPLICATION_JSON)
                    .content("{\"email\":\"user@example.com\",\"password\":\"wrong\"}"))
            .andReturn();
    MvcResult unknownEmail =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(APPLICATION_JSON)
                    .content("{\"email\":\"missing@example.com\",\"password\":\"wrong\"}"))
            .andReturn();
    assertEquals(wrongPassword.getResponse().getStatus(), unknownEmail.getResponse().getStatus());
    assertEquals(
        (String) JsonPath.read(wrongPassword.getResponse().getContentAsString(), "$.error"),
        (String) JsonPath.read(unknownEmail.getResponse().getContentAsString(), "$.error"));
  }

  @Test
  @DisplayName("SPEC §5: disabled account returns 401 same as wrong password")
  void login_withDisabledAccount_returns401() throws Exception {
    userRepository.save(
        new User(
            "disabled@example.com",
            passwordEncoder.encode("password123"),
            "ROLE_USER",
            "IT",
            false));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"disabled@example.com\",\"password\":\"password123\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    assertTrue(hasAuditEvent(AuditEvent.LOGIN_FAILED, "disabled@example.com"));
  }

  // ─── BDD 3.5: JWT auth ──────────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.5: valid JWT allows GET /api/users/me")
  void getMe_withValidToken_returns200() throws Exception {
    mockMvc
        .perform(
            get("/api/users/me")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + login("user@example.com", "password123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("user@example.com"))
        .andExpect(jsonPath("$.role").value("ROLE_USER"));
  }

  // ─── BDD 3.6: No JWT → 401 ──────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.6: no JWT returns 401 unauthorized")
  void getMe_withoutToken_returns401() throws Exception {
    mockMvc
        .perform(get("/api/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("BDD 3.6: expired JWT returns 401 token_expired")
  void getMe_withExpiredToken_returns401TokenExpired() throws Exception {
    mockMvc
        .perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("token_expired"));
  }

  // ─── BDD 3.7: ROLE_USER → 403 on admin ─────────────────────────────────

  @Test
  @DisplayName("BDD 3.7: ROLE_USER on /api/admin/users returns 403")
  void getAdminUsers_withUserRole_returns403() throws Exception {
    mockMvc
        .perform(
            get("/api/admin/users")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + login("user@example.com", "password123")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    assertTrue(hasAuditEvent(AuditEvent.ACCESS_DENIED, "user@example.com"));
  }

  // ─── BDD 3.8: ROLE_ADMIN → 200 on admin ────────────────────────────────

  @Test
  @DisplayName("BDD 3.8: ROLE_ADMIN on /api/admin/users returns 200")
  void getAdminUsers_withAdminRole_returns200() throws Exception {
    mockMvc
        .perform(
            get("/api/admin/users")
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + login("admin@example.com", "admin123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  @DisplayName("BDD 3.8: admin user list does not expose password_hash")
  void getAdminUsers_doesNotExposePasswordHash() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/admin/users")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + login("admin@example.com", "admin123")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(!body.contains("password_hash"));
    assertTrue(!body.contains("passwordHash"));
  }

  // ─── BDD 3.9: actuator health ───────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.9: /actuator/health requires no authentication")
  void actuatorHealth_withoutToken_returns200() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  // ─── BDD 3.10: Flyway migration ─────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.10: Flyway V1 migration created users table with correct structure")
  void flyway_v1_createsUsersAndAuditLogTables() {
    // If we reached here, Flyway ran successfully (Spring context would not start otherwise)
    // Validate by inserting and querying
    assertTrue(userRepository.existsByEmail("user@example.com"));
    // BCrypt hash starts with $2a$
    User user = userRepository.findByEmail("user@example.com").orElseThrow();
    assertTrue(user.getPasswordHash().startsWith("$2a$"));
    assertTrue(passwordEncoder.matches("password123", user.getPasswordHash()));
  }

  // ─── Helpers ────────────────────────────────────────────────────────────

  private String login(String email, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(APPLICATION_JSON)
                    .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
  }

  private String expiredToken() {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject("user@example.com")
        .claim("role", "ROLE_USER")
        .issuedAt(Date.from(now.minusSeconds(120)))
        .expiration(Date.from(now.minusSeconds(60)))
        .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
        .compact();
  }

  private boolean hasAuditEvent(AuditEvent event, String email) {
    List<AuditLog> logs = auditLogRepository.findAll();
    return logs.stream()
        .anyMatch(log -> log.getEvent() == event && email.equals(log.getUserEmail()));
  }
}
