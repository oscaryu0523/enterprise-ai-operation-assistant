package com.enterprise.aiassistant.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationResponse(
    UUID id,
    String title,
    Long ownerId,
    String status,
    OffsetDateTime createdAt,
    List<MessageResponse> messages) {

  public static ConversationResponse of(Conversation c, List<MessageResponse> messages) {
    return new ConversationResponse(
        c.getId(), c.getTitle(), c.getOwnerId(), c.getStatus(), c.getCreatedAt(), messages);
  }

  public static ConversationResponse summary(Conversation c) {
    return new ConversationResponse(
        c.getId(), c.getTitle(), c.getOwnerId(), c.getStatus(), c.getCreatedAt(), null);
  }
}
