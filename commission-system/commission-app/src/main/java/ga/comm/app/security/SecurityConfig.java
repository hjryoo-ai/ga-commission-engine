package ga.comm.app.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

/**
 * 최소 인증·역할 분리 (설계서 §6.6, Phase 16). HTTP Basic + 역할:
 * <ul>
 *   <li><b>ADMIN</b> — 요율/시책 승인, 마감·강제마감, 지급 (돈·상태를 바꾸는 행위),</li>
 *   <li><b>OPERATOR</b> — 배치 잡 실행,</li>
 *   <li><b>VIEWER</b> — 조회·시뮬레이션.</li>
 * </ul>
 * <b>인증 없는 배포 금지</b>: 사용자는 외부 구성({@code app.security.users})에서만 오고, 구성이 없으면
 * 인증 가능한 주체가 없다. 승인 API는 요청 본문 approvedBy가 아니라 인증 주체(principal)를 실승인자로
 * 쓴다(§6.6 실명 요건 완결). 사내 SSO 연동은 후속 — 그때 이 인메모리 사용자를 SSO로 교체한다.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // 상태 없는 REST + Basic — CSRF 토큰 비적용
                .authorizeHttpRequests(auth -> auth
                        // 헬스·레디니스는 오케스트레이터가 인증 없이 조회 (그 외 actuator는 ADMIN)
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // 돈·상태를 바꾸는 승인 API
                        .requestMatchers("/api/rates/**", "/api/incentives/**").hasRole("ADMIN")
                        // 배치 잡 기동
                        .requestMatchers("/api/batch/**").hasAnyRole("OPERATOR", "ADMIN")
                        // 조회·시뮬레이션은 인증된 누구나
                        .requestMatchers("/api/commissions/**", "/api/disclosure/**").authenticated()
                        .anyRequest().authenticated())
                .httpBasic(basic -> {
                });
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // {noop}/{bcrypt} 접두를 지원 — 운영은 {bcrypt} 해시를 외부 구성으로 주입(저장소 비밀 0).
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(SecurityProperties props) {
        List<UserDetails> users = new ArrayList<>();
        for (SecurityProperties.User u : props.getUsers()) {
            users.add(User.withUsername(u.getUsername())
                    .password(u.getPassword())
                    .roles(u.getRole())
                    .build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
