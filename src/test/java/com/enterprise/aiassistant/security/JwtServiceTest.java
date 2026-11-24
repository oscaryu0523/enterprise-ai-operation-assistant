package com.enterprise.aiassistant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// EDD-F02-01 to EDD-F02-03: JWT generation and validation
// Unit test — no Spring context needed
class JwtServiceTest {

  private static final String SECRET = "test-secret-256bit-xxxxxxxxxxxxxxxx";
  private final JwtService jwtService = new JwtService(SECRET, 3600);

  @Test
  @DisplayName("generateToken: contains correct sub and role claims")
  void generateToken_containsCorrectClaims() {
    String token = jwtService.generateToken("user@example.com", "ROLE_USER");

    assertNotNull(token);
    assertEquals("user@example.com", jwtService.extractEmail(token));
    assertEquals("ROLE_USER", jwtService.extractRole(token));
    assertTrue(jwtService.getExpirationSeconds() > 0);
  }

  @Test
  @DisplayName("generateToken: payload does not contain password or secret")
  void generateToken_doesNotContainSensitiveData() {
    String token = jwtService.generateToken("user@example.com", "ROLE_USER");
    String payload =
        new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

    assertTrue(!payload.contains("password"));
    assertTrue(!payload.contains("secret"));
  }

  @Test
  @DisplayName("EDD-F02-01: validateToken succeeds for a valid token")
  void validateToken_withValidToken_succeeds() {
    String token = jwtService.generateToken("user@example.com", "ROLE_USER");
    // should not throw
    jwtService.validateToken(token);
    assertEquals("user@example.com", jwtService.extractEmail(token));
  }

  @Test
  @DisplayName("EDD-F02-02: validateToken throws ExpiredJwtException for expired token")
  void validateToken_withExpiredToken_throwsExpiredJwtException() {
    Instant now = Instant.now();
    String expiredToken =
        Jwts.builder()
            .subject("user@example.com")
            .issuedAt(Date.from(now.minusSeconds(120)))
            .expiration(Date.from(now.minusSeconds(60)))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();

    assertThrows(ExpiredJwtException.class, () -> jwtService.validateToken(expiredToken));
  }

  @Test
  @DisplayName("validateToken: malformed token throws MalformedJwtException")
  void validateToken_withMalformedToken_throwsMalformedJwtException() {
    assertThrows(MalformedJwtException.class, () -> jwtService.validateToken("not.a.jwt"));
  }

  @Test
  @DisplayName("EDD-F02-03: validateToken throws SignatureException for tampered token")
  void validateToken_withTamperedSignature_throwsSignatureException() {
    String[] parts = jwtService.generateToken("user@example.com", "ROLE_USER").split("\\.");
    parts[2] = (parts[2].charAt(0) == 'a' ? "b" : "a") + parts[2].substring(1);

    assertThrows(SignatureException.class, () -> jwtService.validateToken(String.join(".", parts)));
  }
}
