package com.enterprise.aiassistant.security;

import com.enterprise.aiassistant.audit.AuditEvent;
import com.enterprise.aiassistant.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final JwtAuthFilter jwtAuthFilter;
  private final UserDetailsService userDetailsService;
  private final AuditService auditService;

  public SecurityConfig(
      JwtAuthFilter jwtAuthFilter,
      UserDetailsService userDetailsService,
      AuditService auditService) {
    this.jwtAuthFilter = jwtAuthFilter;
    this.userDetailsService = userDetailsService;
    this.auditService = auditService;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/auth/**",
                        "/actuator/health",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasAuthority("ROLE_ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(this::handleUnauthorized)
                    .accessDeniedHandler(this::handleForbidden))
        .authenticationProvider(authenticationProvider())
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public AuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder());
    return provider;
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
      throws Exception {
    return config.getAuthenticationManager();
  }

  private void handleUnauthorized(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    writeError(
        response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
  }

  private void handleForbidden(
      HttpServletRequest request,
      HttpServletResponse response,
      org.springframework.security.access.AccessDeniedException accessDeniedException)
      throws IOException {
    Object principal =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication()
            .getPrincipal();
    if (principal instanceof String email) {
      String ip = request.getRemoteAddr();
      auditService.record(AuditEvent.ACCESS_DENIED, null, email, ip, null);
    }
    writeError(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Access denied");
  }

  private void writeError(HttpServletResponse response, int status, String error, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"code\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}"
                .formatted(error, message, Instant.now()));
  }
}
