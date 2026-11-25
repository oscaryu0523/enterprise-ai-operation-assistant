package com.enterprise.aiassistant.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MessageResponse(
    UUID id, UUID conversationId, String role, String content, OffsetDateTime createdAt) {

  public static MessageResponse of(Message m) {
    return new MessageResponse(
        m.getId(), m.getConversationId(), m.getRole(), m.getContent(), m.getCreatedAt());
  }
}
