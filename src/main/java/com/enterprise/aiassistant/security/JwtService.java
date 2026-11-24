package com.enterprise.aiassistant.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private final SecretKey key;
  private final int expirationSeconds;

  public JwtService(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.expiration-seconds}") int expirationSeconds) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.expirationSeconds = expirationSeconds;
  }

  public String generateToken(String email, String role) {
    long nowMs = System.currentTimeMillis();
    return Jwts.builder()
        .subject(email)
        .claim("role", role)
        .issuedAt(new Date(nowMs))
        .expiration(new Date(nowMs + (long) expirationSeconds * 1000))
        .signWith(key)
        .compact();
  }

  // Throws JwtException subclasses on failure (ExpiredJwtException, SignatureException, etc.)
  public void validateToken(String token) {
    parseClaims(token);
  }

  public String extractEmail(String token) {
    return parseClaims(token).getSubject();
  }

  public String extractRole(String token) {
    return parseClaims(token).get("role", String.class);
  }

  public int getExpirationSeconds() {
    return expirationSeconds;
  }

  private Claims parseClaims(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
