package com.enterprise.aiassistant.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("expires_in") int expiresIn,
    String email,
    String role) {}
