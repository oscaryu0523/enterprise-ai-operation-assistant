package com.enterprise.aiassistant.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
    @NotBlank(message = "title must not be blank") @Size(max = 255) String title) {}
