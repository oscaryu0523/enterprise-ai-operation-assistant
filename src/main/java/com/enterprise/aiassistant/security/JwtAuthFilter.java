package com.enterprise.aiassistant.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

  private final JwtService jwtService;

  public JwtAuthFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      chain.doFilter(request, response);
      return;
    }

    String token = header.substring(7);
    try {
      jwtService.validateToken(token);
      String email = jwtService.extractEmail(token);
      String role = jwtService.extractRole(token);
      UsernamePasswordAuthenticationToken auth =
          new UsernamePasswordAuthenticationToken(
              email, null, java.util.List.of(new SimpleGrantedAuthority(role)));
      SecurityContextHolder.getContext().setAuthentication(auth);
      chain.doFilter(request, response);
    } catch (ExpiredJwtException e) {
      writeError(
          response, HttpServletResponse.SC_UNAUTHORIZED, "token_expired", "Token has expired");
    } catch (JwtException e) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token", "Invalid token");
    }
  }

  private void writeError(HttpServletResponse response, int status, String error, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"error\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}"
                .formatted(error, message, Instant.now()));
  }
}
