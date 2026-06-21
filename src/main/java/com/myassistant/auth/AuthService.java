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

  /**
   * 신규 사용자를 등록하고 JWT를 발급하여 반환한다.
   *
   * @param request 사용자명/비밀번호를 담은 회원가입 요청
   * @return 발급된 JWT를 담은 AuthResponse
   * @throws IllegalArgumentException 이미 사용 중인 아이디일 경우
   */
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

  /**
   * 사용자명/비밀번호를 검증하고 로그인 성공 시 JWT를 발급하여 반환한다.
   *
   * @param request 사용자명/비밀번호를 담은 로그인 요청
   * @return 발급된 JWT를 담은 AuthResponse
   * @throws IllegalArgumentException 아이디가 존재하지 않거나 비밀번호가 일치하지 않을 경우
   */
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
