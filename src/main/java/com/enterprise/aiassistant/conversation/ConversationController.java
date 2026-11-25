package com.enterprise.aiassistant.conversation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

  private final ConversationService conversationService;

  public ConversationController(ConversationService conversationService) {
    this.conversationService = conversationService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ConversationResponse createConversation(
      @Valid @RequestBody CreateConversationRequest request,
      Authentication auth,
      HttpServletRequest httpRequest) {
    return conversationService.createConversation(request, auth, httpRequest.getRemoteAddr());
  }

  @GetMapping
  public List<ConversationResponse> listConversations(Authentication auth) {
    return conversationService.listConversations(auth);
  }

  @GetMapping("/{id}")
  public ConversationResponse getConversation(@PathVariable UUID id, Authentication auth) {
    return conversationService.getConversation(id, auth);
  }

  @PostMapping("/{id}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  public MessageResponse addMessage(
      @PathVariable UUID id, @Valid @RequestBody AddMessageRequest request, Authentication auth) {
    return conversationService.addMessage(id, request, auth);
  }

  @GetMapping("/{id}/messages")
  public List<MessageResponse> getMessages(@PathVariable UUID id, Authentication auth) {
    return conversationService.getMessages(id, auth);
  }
}
