package com.enterprise.aiassistant.conversation;

import com.enterprise.aiassistant.audit.AuditEvent;
import com.enterprise.aiassistant.audit.AuditService;
import com.enterprise.aiassistant.user.User;
import com.enterprise.aiassistant.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

  private final ConversationRepository conversationRepository;
  private final MessageRepository messageRepository;
  private final UserRepository userRepository;
  private final AuditService auditService;

  public ConversationService(
      ConversationRepository conversationRepository,
      MessageRepository messageRepository,
      UserRepository userRepository,
      AuditService auditService) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.userRepository = userRepository;
    this.auditService = auditService;
  }

  @Transactional
  public ConversationResponse createConversation(
      CreateConversationRequest request, Authentication auth, String ipAddress) {
    User caller = resolveUser(auth);
    Conversation conversation =
        conversationRepository.save(new Conversation(caller.getId(), request.title()));
    auditService.record(
        AuditEvent.CONVERSATION_CREATED,
        caller.getId(),
        caller.getEmail(),
        ipAddress,
        "{\"conversationId\":\"" + conversation.getId() + "\"}");
    return ConversationResponse.summary(conversation);
  }

  public List<ConversationResponse> listConversations(Authentication auth) {
    User caller = resolveUser(auth);
    boolean isAdmin = hasRole(auth, "ROLE_ADMIN");
    if (isAdmin) {
      return conversationRepository.findAll().stream().map(ConversationResponse::summary).toList();
    }
    return conversationRepository.findByOwnerIdOrderByCreatedAtDesc(caller.getId()).stream()
        .map(ConversationResponse::summary)
        .toList();
  }

  public ConversationResponse getConversation(UUID id, Authentication auth) {
    Conversation conversation = findAndCheckAccess(id, auth);
    List<MessageResponse> messages =
        messageRepository.findByConversationIdOrderByCreatedAtAsc(id).stream()
            .map(MessageResponse::of)
            .toList();
    return ConversationResponse.of(conversation, messages);
  }

  @Transactional
  public MessageResponse addMessage(
      UUID conversationId, AddMessageRequest request, Authentication auth) {
    findAndCheckAccess(conversationId, auth);
    Message message =
        messageRepository.save(new Message(conversationId, request.role(), request.content()));
    return MessageResponse.of(message);
  }

  public List<MessageResponse> getMessages(UUID conversationId, Authentication auth) {
    findAndCheckAccess(conversationId, auth);
    return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
        .map(MessageResponse::of)
        .toList();
  }

  private Conversation findAndCheckAccess(UUID id, Authentication auth) {
    Conversation conversation =
        conversationRepository
            .findById(id)
            .orElseThrow(() -> new ConversationNotFoundException(id));
    if (!hasRole(auth, "ROLE_ADMIN")) {
      User caller = resolveUser(auth);
      if (!conversation.getOwnerId().equals(caller.getId())) {
        throw new ConversationForbiddenException();
      }
    }
    return conversation;
  }

  private User resolveUser(Authentication auth) {
    String email = (String) auth.getPrincipal();
    return userRepository.findByEmail(email).orElseThrow();
  }

  private boolean hasRole(Authentication auth, String role) {
    return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(role));
  }
}
