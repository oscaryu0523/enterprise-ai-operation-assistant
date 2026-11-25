package com.enterprise.aiassistant.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddMessageRequest(
    @NotBlank(message = "role must not be blank")
        @Pattern(
            regexp = "user|assistant|system",
            message = "role must be one of: user, assistant, system")
        String role,
    @NotBlank(message = "content must not be blank") @Size(max = 32000) String content) {}
