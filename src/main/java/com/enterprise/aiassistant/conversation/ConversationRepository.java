package com.enterprise.aiassistant.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

  List<Conversation> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);
}
