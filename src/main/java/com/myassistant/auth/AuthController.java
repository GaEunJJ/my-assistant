package com.myassistant.auth;

import com.myassistant.auth.dto.AuthResponse;
import com.myassistant.auth.dto.LoginRequest;
import com.myassistant.auth.dto.RegisterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 회원가입 / 로그인 API
 * POST /api/auth/register — 회원가입 후 JWT 반환
 * POST /api/auth/login    — 로그인 후 JWT 반환
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  @PostMapping("/register")
  public Mono<ResponseEntity<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
    return authService.register(request)
        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
  }

  @PostMapping("/login")
  public Mono<ResponseEntity<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request)
        .map(ResponseEntity::ok);
  }
}
