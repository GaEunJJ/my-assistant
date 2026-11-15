package com.myassistant.security;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationWebFilter implements WebFilter {

  private final JwtProvider jwtProvider;
  private final ReactiveUserDetailsService userDetailsService;

  /**
   * 요청 헤더의 JWT를 검증하여 인증 정보를 SecurityContext에 주입한다.
   *
   * @param exchange 현재 요청/응답 컨텍스트
   * @param chain    다음 필터 체인
   * @return 필터 체인 처리 완료를 나타내는 Mono
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String token = resolveToken(exchange);

    if (token == null || !jwtProvider.isValid(token)) {
      return chain.filter(exchange);
    }

    String username = jwtProvider.extractUsername(token);

    return userDetailsService.findByUsername(username)
        .map(userDetails -> Optional.of(new UsernamePasswordAuthenticationToken(
            userDetails, null, userDetails.getAuthorities()
        )))
        // 토큰은 유효하지만 사용자가 DB에 없으면(탈퇴/DB 초기화 등) 500이 나지 않도록
        // 인증 없이 체인을 이어가 401로 처리되게 한다
        .onErrorResume(UsernameNotFoundException.class, e -> Mono.empty())
        .defaultIfEmpty(Optional.empty())
        // chain.filter()는 항상 빈 Mono<Void>로 끝나므로, 그 뒤에 switchIfEmpty를 붙이면 체인이 두 번 실행된다.
        // 그래서 인증 여부를 Optional로 먼저 확정한 뒤 체인을 정확히 한 번만 실행한다
        .flatMap(auth -> auth
            .map(authToken -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authToken)))
            .orElseGet(() -> chain.filter(exchange)));
  }

  /**
   * Authorization 헤더에서 Bearer 토큰을 추출한다.
   *
   * @param exchange 현재 요청/응답 컨텍스트
   * @return 추출된 JWT 문자열, 없으면 null
   */
  private String resolveToken(ServerWebExchange exchange) {
    String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
      return header.substring(7);
    }
    return null;
  }
}
