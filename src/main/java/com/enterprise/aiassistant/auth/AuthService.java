package com.enterprise.aiassistant.auth;

import com.enterprise.aiassistant.audit.AuditEvent;
import com.enterprise.aiassistant.audit.AuditService;
import com.enterprise.aiassistant.security.JwtService;
import com.enterprise.aiassistant.user.User;
import com.enterprise.aiassistant.user.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private static final String DUMMY_HASH =
      "$2a$10$dummyhashXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";

  private final UserRepository userRepository;
  private final JwtService jwtService;
  private final AuditService auditService;
  private final PasswordEncoder passwordEncoder;

  public AuthService(
      UserRepository userRepository,
      JwtService jwtService,
      AuditService auditService,
      PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.jwtService = jwtService;
    this.auditService = auditService;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public AuthResponse register(RegisterRequest request, String ipAddress) {
    if (userRepository.existsByEmail(request.email())) {
      throw new EmailAlreadyExistsException(request.email());
    }
    User user =
        new User(
            request.email(),
            passwordEncoder.encode(request.password()),
            "ROLE_USER",
            request.department(),
            true);
    userRepository.save(user);
    auditService.record(AuditEvent.REGISTER, user.getId(), user.getEmail(), ipAddress, null);
    String token = jwtService.generateToken(user.getEmail(), user.getRole());
    return new AuthResponse(
        token, "Bearer", jwtService.getExpirationSeconds(), user.getEmail(), user.getRole());
  }

  public AuthResponse login(LoginRequest request, String ipAddress) {
    // Constant-time lookup to prevent user enumeration via timing attacks
    User user = userRepository.findByEmail(request.email()).orElse(null);
    String hashToCheck = user != null ? user.getPasswordHash() : DUMMY_HASH;
    boolean matches = passwordEncoder.matches(request.password(), hashToCheck);

    if (user == null || !matches) {
      auditService.record(AuditEvent.LOGIN_FAILED, null, request.email(), ipAddress, null);
      throw new BadCredentialsException("Invalid email or password");
    }

    auditService.record(AuditEvent.LOGIN_SUCCESS, user.getId(), user.getEmail(), ipAddress, null);
    String token = jwtService.generateToken(user.getEmail(), user.getRole());
    return new AuthResponse(
        token, "Bearer", jwtService.getExpirationSeconds(), user.getEmail(), user.getRole());
  }
}
