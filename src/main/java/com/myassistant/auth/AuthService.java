package com.myassistant.auth;

import com.myassistant.auth.dto.AuthResponse;
import com.myassistant.auth.dto.LoginRequest;
import com.myassistant.auth.dto.RegisterRequest;
import com.myassistant.security.JwtProvider;
import com.myassistant.user.User;
import com.myassistant.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtProvider jwtProvider;

  public Mono<AuthResponse> register(RegisterRequest request) {
    return userRepository.existsByUsername(request.username())
        .flatMap(exists -> {
          if (exists) {
            return Mono.error(new IllegalArgumentException("이미 사용 중인 아이디입니다."));
          }
          User user = User.builder()
              .username(request.username())
              .password(passwordEncoder.encode(request.password()))
              .build();
          return userRepository.save(user);
        })
        .map(user -> new AuthResponse(jwtProvider.generateToken(user.getUsername())));
  }

  public Mono<AuthResponse> login(LoginRequest request) {
    return userRepository.findByUsername(request.username())
        .switchIfEmpty(Mono.error(new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다.")))
        .flatMap(user -> {
          if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            return Mono.error(new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다."));
          }
          return Mono.just(new AuthResponse(jwtProvider.generateToken(user.getUsername())));
        });
  }
}
