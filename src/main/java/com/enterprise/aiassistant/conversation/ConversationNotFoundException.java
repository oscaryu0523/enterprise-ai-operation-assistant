package com.enterprise.aiassistant.conversation;

import java.util.UUID;

public class ConversationNotFoundException extends RuntimeException {

  public ConversationNotFoundException(UUID id) {
    super("Conversation not found: " + id);
  }
}
