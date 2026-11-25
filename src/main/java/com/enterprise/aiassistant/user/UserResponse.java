package com.enterprise.aiassistant.user;

import java.time.OffsetDateTime;

public record UserResponse(
    Long id,
    String email,
    String role,
    String department,
    boolean enabled,
    OffsetDateTime createdAt) {

  public static UserResponse from(User user) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getRole(),
        user.getDepartment(),
        user.isEnabled(),
        user.getCreatedAt());
  }
}
