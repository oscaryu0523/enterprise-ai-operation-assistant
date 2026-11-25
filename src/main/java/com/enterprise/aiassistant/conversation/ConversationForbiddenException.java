package com.enterprise.aiassistant.conversation;

public class ConversationForbiddenException extends RuntimeException {

  public ConversationForbiddenException() {
    super("Access to this conversation is forbidden");
  }
}
