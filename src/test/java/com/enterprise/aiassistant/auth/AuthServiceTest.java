package com.enterprise.aiassistant.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.aiassistant.audit.AuditEvent;
import com.enterprise.aiassistant.audit.AuditService;
import com.enterprise.aiassistant.security.JwtService;
import com.enterprise.aiassistant.user.User;
import com.enterprise.aiassistant.user.UserRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// BDD 3.3-3.4, EDD-F02-04 to EDD-F02-05
// Unit test — no Spring context, no DB
class AuthServiceTest {

  private static final String TEST_SECRET = "test-secret-256bit-xxxxxxxxxxxxxxxx";

  @Test
  @DisplayName("EDD-F02-04 + BDD 3.3: login succeeds with correct password")
  void login_withValidCredentials_returnsAuthResponse() {
    User user = new User("user@example.com", "bcrypt-hash", "ROLE_USER", "IT", true);
    Deps deps = deps(Optional.of(user), true);

    AuthResponse response =
        deps.authService().login(new LoginRequest("user@example.com", "correct"), "127.0.0.1");

    assertNotNull(response.accessToken());
    assertEquals("mock.jwt.token", response.accessToken());
    assertEquals("Bearer", response.tokenType());
    assertEquals(3600, response.expiresIn());
    assertEquals(AuditEvent.LOGIN_SUCCESS, deps.auditService().records().getFirst().event());
    assertEquals("user@example.com", deps.auditService().records().getFirst().email());
  }

  @Test
  @DisplayName("EDD-F02-05 + BDD 3.4: login fails with wrong password")
  void login_withWrongPassword_throwsBadCredentials() {
    User user = new User("user@example.com", "bcrypt-hash", "ROLE_USER", "IT", true);
    Deps deps = deps(Optional.of(user), false);

    assertThrows(
        BadCredentialsException.class,
        () -> deps.authService().login(new LoginRequest("user@example.com", "wrong"), "127.0.0.1"));

    assertEquals(AuditEvent.LOGIN_FAILED, deps.auditService().records().getFirst().event());
    assertEquals(0, deps.jwtService().generatedTokenCount);
  }

  @Test
  @DisplayName(
      "BDD 3.4: unknown email returns same exception as wrong password (no user enumeration)")
  void login_withUnknownEmail_throwsSameException() {
    Deps deps = deps(Optional.empty(), false);

    BadCredentialsException ex =
        assertThrows(
            BadCredentialsException.class,
            () ->
                deps.authService()
                    .login(new LoginRequest("unknown@example.com", "any"), "127.0.0.1"));

    assertEquals("Invalid email or password", ex.getMessage());
    assertEquals(AuditEvent.LOGIN_FAILED, deps.auditService().records().getFirst().event());
    assertEquals("unknown@example.com", deps.auditService().records().getFirst().email());
    // Constant-time: password encoder must still be called (dummy hash)
    assertNotNull(deps.passwordEncoder().lastEncodedPassword);
  }

  @Test
  @DisplayName("BDD 3.3: password verification uses BCrypt, not plaintext comparison")
  void login_passwordVerification_usesBCrypt() {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    String passwordHash = encoder.encode("correct");
    User user = new User("user@example.com", passwordHash, "ROLE_USER", "IT", true);
    RecordingJwtService jwtService = new RecordingJwtService();
    RecordingAuditService auditService = new RecordingAuditService();
    AuthService authService =
        new AuthService(userRepository(Optional.of(user)), jwtService, auditService, encoder);

    assertTrue(passwordHash.startsWith("$2a$"));
    assertNotNull(authService.login(new LoginRequest("user@example.com", "correct"), "127.0.0.1"));
    assertThrows(
        BadCredentialsException.class,
        () -> authService.login(new LoginRequest("user@example.com", "wrong"), "127.0.0.1"));
  }

  private Deps deps(Optional<User> user, boolean passwordMatches) {
    UserRepository repo = userRepository(user);
    RecordingJwtService jwtService = new RecordingJwtService();
    RecordingAuditService auditService = new RecordingAuditService();
    RecordingPasswordEncoder passwordEncoder = new RecordingPasswordEncoder(passwordMatches);
    AuthService authService = new AuthService(repo, jwtService, auditService, passwordEncoder);
    return new Deps(authService, jwtService, auditService, passwordEncoder);
  }

  private UserRepository userRepository(Optional<User> user) {
    return (UserRepository)
        Proxy.newProxyInstance(
            UserRepository.class.getClassLoader(),
            new Class<?>[] {UserRepository.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("findByEmail")) return user;
              if (method.getName().equals("existsByEmail")) return user.isPresent();
              if (method.getName().equals("save")) return arguments[0];
              if (method.getName().equals("toString")) return "FakeUserRepository";
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private record Deps(
      AuthService authService,
      RecordingJwtService jwtService,
      RecordingAuditService auditService,
      RecordingPasswordEncoder passwordEncoder) {}

  private static class RecordingJwtService extends JwtService {

    int generatedTokenCount;

    RecordingJwtService() {
      super(TEST_SECRET, 3600);
    }

    @Override
    public String generateToken(String email, String role) {
      generatedTokenCount++;
      return "mock.jwt.token";
    }
  }

  private static class RecordingAuditService extends AuditService {

    private final List<AuditRecord> records = new ArrayList<>();

    RecordingAuditService() {
      super(null);
    }

    @Override
    public void record(
        AuditEvent event, Long userId, String email, String ipAddress, String details) {
      records.add(new AuditRecord(event, email, ipAddress, details));
    }

    List<AuditRecord> records() {
      return records;
    }
  }

  private static class RecordingPasswordEncoder implements PasswordEncoder {

    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder(10);
    private final boolean matchesResult;
    private CharSequence lastRawPassword;
    String lastEncodedPassword;

    RecordingPasswordEncoder(boolean matchesResult) {
      this.matchesResult = matchesResult;
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      lastRawPassword = rawPassword;
      lastEncodedPassword = encodedPassword;
      return matchesResult;
    }
  }

  private record AuditRecord(AuditEvent event, String email, String ipAddress, String details) {}
}
