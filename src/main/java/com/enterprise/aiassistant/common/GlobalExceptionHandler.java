package com.enterprise.aiassistant.common;

import com.enterprise.aiassistant.auth.EmailAlreadyExistsException;
import com.enterprise.aiassistant.conversation.ConversationForbiddenException;
import com.enterprise.aiassistant.conversation.ConversationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(EmailAlreadyExistsException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ErrorResponse handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
    return ErrorResponse.of("EMAIL_ALREADY_EXISTS", ex.getMessage());
  }

  @ExceptionHandler(BadCredentialsException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public ErrorResponse handleBadCredentials(BadCredentialsException ex) {
    return ErrorResponse.of("INVALID_CREDENTIALS", "Invalid email or password");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");
    return ErrorResponse.of("VALIDATION_ERROR", message);
  }

  @ExceptionHandler(ConversationNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleConversationNotFound(ConversationNotFoundException ex) {
    return ErrorResponse.of("NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(ConversationForbiddenException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ErrorResponse handleConversationForbidden(ConversationForbiddenException ex) {
    return ErrorResponse.of("FORBIDDEN", ex.getMessage());
  }
}
