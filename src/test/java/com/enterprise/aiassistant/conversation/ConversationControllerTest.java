package com.enterprise.aiassistant.conversation;

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
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// BDD F03 integration tests — Conversation Management
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ConversationControllerTest {

  private static final String TEST_SECRET = "test-secret-256bit-xxxxxxxxxxxxxxxx";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired ConversationRepository conversationRepository;
  @Autowired MessageRepository messageRepository;
  @Autowired AuditLogRepository auditLogRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    if (!POSTGRES.isRunning()) return;
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  private User userA;
  private User userB;
  private User adminUser;

  @BeforeEach
  void setUp() {
    messageRepository.deleteAll();
    conversationRepository.deleteAll();
    auditLogRepository.deleteAll();
    userRepository.deleteAll();

    userA =
        userRepository.save(
            new User("a@example.com", passwordEncoder.encode("pw"), "ROLE_USER", null, true));
    userB =
        userRepository.save(
            new User("b@example.com", passwordEncoder.encode("pw"), "ROLE_USER", null, true));
    adminUser =
        userRepository.save(
            new User("admin@example.com", passwordEncoder.encode("pw"), "ROLE_ADMIN", null, true));
  }

  private String jwt(String email, String role) {
    return Jwts.builder()
        .subject(email)
        .claim("role", role)
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(Instant.now().plusSeconds(3600)))
        .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
        .compact();
  }

  // ── 3.1 建立對話 ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.1a: 已認證使用者建立對話 → 201，含 id/title/ownerId/status/createdAt")
  void createConversation_success() throws Exception {
    mockMvc
        .perform(
            post("/api/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER"))
                .contentType(APPLICATION_JSON)
                .content("{\"title\":\"我的第一個對話\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.title").value("我的第一個對話"))
        .andExpect(jsonPath("$.ownerId").value(userA.getId()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.createdAt").isNotEmpty());

    // audit log 應寫入 CONVERSATION_CREATED，userId 為 userA
    List<AuditLog> logs = auditLogRepository.findAll();
    boolean hasAudit =
        logs.stream()
            .anyMatch(
                l ->
                    l.getEvent() == AuditEvent.CONVERSATION_CREATED
                        && userA.getId().equals(l.getUserId()));
    org.junit.jupiter.api.Assertions.assertTrue(
        hasAudit, "Expected CONVERSATION_CREATED audit log");
  }

  @Test
  @DisplayName("BDD 3.1b: 空標題 → 400 VALIDATION_ERROR")
  void createConversation_emptyTitle_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER"))
                .contentType(APPLICATION_JSON)
                .content("{\"title\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("BDD 3.1c: 未認證 → 401 UNAUTHORIZED")
  void createConversation_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(
            post("/api/conversations")
                .contentType(APPLICATION_JSON)
                .content("{\"title\":\"test\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  // ── 3.2 列出對話 ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.2a: 使用者 A 有 2 個對話，只看到自己的，每筆含 id/title/status/createdAt，不含 messages")
  void listConversations_returnsOnlyOwn() throws Exception {
    conversationRepository.save(new Conversation(userA.getId(), "對話 A1"));
    conversationRepository.save(new Conversation(userA.getId(), "對話 A2"));
    conversationRepository.save(new Conversation(userB.getId(), "對話 B1"));

    mockMvc
        .perform(
            get("/api/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").isNotEmpty())
        .andExpect(jsonPath("$[0].title").isNotEmpty())
        .andExpect(jsonPath("$[0].status").isNotEmpty())
        .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
        .andExpect(jsonPath("$[0].messages").doesNotExist());
  }

  @Test
  @DisplayName("BDD 3.2b: 無對話時回傳空陣列")
  void listConversations_empty() throws Exception {
    mockMvc
        .perform(
            get("/api/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  // ── 3.3 查詢單一對話 ──────────────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.3a: 查詢自己的對話 → 200，含空 messages 陣列")
  void getConversation_success() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userA.getId(), "對話 A"));

    mockMvc
        .perform(
            get("/api/conversations/" + c.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(c.getId().toString()))
        .andExpect(jsonPath("$.messages").isArray())
        .andExpect(jsonPath("$.messages.length()").value(0));
  }

  @Test
  @DisplayName("BDD 3.3b: 查詢他人對話 → 403 FORBIDDEN")
  void getConversation_otherUser_returns403() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userB.getId(), "對話 B"));

    mockMvc
        .perform(
            get("/api/conversations/" + c.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("BDD 3.3c: 查詢不存在的對話 → 404 NOT_FOUND")
  void getConversation_notFound_returns404() throws Exception {
    mockMvc
        .perform(
            get("/api/conversations/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ── 3.4 新增訊息 ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.4a: 新增 user 訊息 → 201")
  void addMessage_user_success() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userA.getId(), "對話 A"));

    mockMvc
        .perform(
            post("/api/conversations/" + c.getId() + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER"))
                .contentType(APPLICATION_JSON)
                .content("{\"role\":\"user\",\"content\":\"請說明 JWT 的用途\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.conversationId").value(c.getId().toString()))
        .andExpect(jsonPath("$.role").value("user"))
        .andExpect(jsonPath("$.content").value("請說明 JWT 的用途"))
        .andExpect(jsonPath("$.createdAt").isNotEmpty());
  }

  @Test
  @DisplayName("BDD 3.4b: 新增 assistant 訊息 → 201")
  void addMessage_assistant_success() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userA.getId(), "對話 A"));

    mockMvc
        .perform(
            post("/api/conversations/" + c.getId() + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER"))
                .contentType(APPLICATION_JSON)
                .content("{\"role\":\"assistant\",\"content\":\"JWT 是一種...\"  }"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("assistant"));
  }

  @Test
  @DisplayName("BDD 3.4c: role 無效 → 400 VALIDATION_ERROR")
  void addMessage_invalidRole_returns400() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userA.getId(), "對話 A"));

    mockMvc
        .perform(
            post("/api/conversations/" + c.getId() + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER"))
                .contentType(APPLICATION_JSON)
                .content("{\"role\":\"invalid_role\",\"content\":\"test\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("BDD 3.4d: 新增訊息至他人對話 → 403 FORBIDDEN")
  void addMessage_otherUser_returns403() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userB.getId(), "對話 B"));

    mockMvc
        .perform(
            post("/api/conversations/" + c.getId() + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER"))
                .contentType(APPLICATION_JSON)
                .content("{\"role\":\"user\",\"content\":\"test\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("BDD 3.4e: 新增訊息至不存在的對話 → 404 NOT_FOUND")
  void addMessage_conversationNotFound_returns404() throws Exception {
    mockMvc
        .perform(
            post("/api/conversations/" + UUID.randomUUID() + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER"))
                .contentType(APPLICATION_JSON)
                .content("{\"role\":\"user\",\"content\":\"test\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ── 3.5 查詢訊息列表 ──────────────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.5: 3 則訊息依 createdAt 升序排列")
  void getMessages_orderedByCreatedAt() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userA.getId(), "對話 A"));
    messageRepository.save(new Message(c.getId(), "user", "第一則"));
    messageRepository.save(new Message(c.getId(), "assistant", "第二則"));
    messageRepository.save(new Message(c.getId(), "user", "第三則"));

    mockMvc
        .perform(
            get("/api/conversations/" + c.getId() + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].role").value("user"))
        .andExpect(jsonPath("$[1].role").value("assistant"))
        .andExpect(jsonPath("$[2].role").value("user"));
  }

  // ── 3.6 ADMIN 跨使用者存取 ───────────────────────────────────────────────

  @Test
  @DisplayName("BDD 3.6a: ADMIN 可查詢任意使用者的對話")
  void admin_canGetAnyConversation() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userA.getId(), "對話 A"));

    mockMvc
        .perform(
            get("/api/conversations/" + c.getId())
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + jwt("admin@example.com", "ROLE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(c.getId().toString()));
  }

  @Test
  @DisplayName("BDD 3.6b: ADMIN 可新增訊息至任意對話")
  void admin_canAddMessageToAnyConversation() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userA.getId(), "對話 A"));

    mockMvc
        .perform(
            post("/api/conversations/" + c.getId() + "/messages")
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + jwt("admin@example.com", "ROLE_ADMIN"))
                .contentType(APPLICATION_JSON)
                .content("{\"role\":\"assistant\",\"content\":\"Admin reply\"}"))
        .andExpect(status().isCreated());
  }

  // ── Edge: content 長度邊界 ───────────────────────────────────────────────

  @Test
  @DisplayName("Edge: content 超過 32000 字元 → 400 VALIDATION_ERROR")
  void addMessage_contentTooLong_returns400() throws Exception {
    Conversation c = conversationRepository.save(new Conversation(userA.getId(), "對話 A"));
    String longContent = "x".repeat(32001);

    mockMvc
        .perform(
            post("/api/conversations/" + c.getId() + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt("a@example.com", "ROLE_USER"))
                .contentType(APPLICATION_JSON)
                .content("{\"role\":\"user\",\"content\":\"" + longContent + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
