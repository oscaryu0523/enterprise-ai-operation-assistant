package com.enterprise.aiassistant.user;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

  private final UserRepository userRepository;

  public UserController(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @GetMapping("/users/me")
  public UserResponse me(Authentication authentication) {
    String email = (String) authentication.getPrincipal();
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    return UserResponse.from(user);
  }

  @GetMapping("/admin/users")
  public List<UserResponse> listUsers() {
    return userRepository.findAll().stream().map(UserResponse::from).toList();
  }
}
