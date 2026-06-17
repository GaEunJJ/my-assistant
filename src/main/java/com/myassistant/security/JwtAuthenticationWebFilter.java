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

  private String resolveToken(ServerWebExchange exchange) {
    String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
      return header.substring(7);
    }
    return null;
  }
}
