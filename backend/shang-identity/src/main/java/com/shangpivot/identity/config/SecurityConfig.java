package com.shangpivot.identity.config;

import com.shangpivot.identity.filter.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RestAuthEntryPoint restAuthEntryPoint;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(restAuthEntryPoint)
                        .accessDeniedHandler(restAuthEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        // SSE（SseEmitter）等异步请求完成时的 ASYNC 派发会重入过滤链，
                        // 此时线程无认证上下文 → 需放行派发类型，否则流式响应被 401 覆盖（V3.0 智能客服）
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(
                                "/admin/auth/login",
                                "/admin/auth/refresh",
                                "/api/auth/**",
                                "/doc.html",
                                "/knife4j/**",
                                "/webjars/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/favicon.ico",
                                "/error"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/admin/files/view",
                                "/api/files/view",
                                "/api/home",
                                "/api/site/brand",
                                "/api/products",
                                "/api/products/**",
                                "/api/categories",
                                "/api/search"
                        ).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/admin/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
