package com.myassistant.security;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
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
        .map(userDetails -> new UsernamePasswordAuthenticationToken(
            userDetails, null, userDetails.getAuthorities()
        ))
        .flatMap(auth ->
            chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
        );
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
