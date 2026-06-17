package com.myassistant.security;

import com.myassistant.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements ReactiveUserDetailsService {

  private final UserRepository userRepository;

  @Override
  public Mono<UserDetails> findByUsername(String username) {
    return userRepository.findByUsername(username)
        .switchIfEmpty(Mono.error(new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username)))
        .map(user -> User.withUsername(user.getUsername())
            .password(user.getPassword())
            .roles("USER")
            .build()
        );
  }
}
