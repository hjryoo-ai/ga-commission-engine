package ga.comm.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * 내부 API 서비스 토큰(Phase E3, 계약 securitySchemes.serviceToken = HTTP Bearer). {@code Authorization: Bearer <token>}의
 * SHA-256을 설정값과 <b>상수 시간</b>으로 비교해 맞으면 ROLE_SERVICE로 인증한다. 틀리거나 없으면 인증하지 않고 넘겨
 * 인가 단계에서 401({@code UNAUTHENTICATED})이 된다. 토큰 원문은 로그·예외에 남기지 않는다. 운영은 mTLS 병행(ga-disclosure 설계서 §9).
 */
public final class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String ROLE = "SERVICE";
    private static final String PREFIX = "Bearer ";

    private final byte[] expectedSha256;

    public ServiceTokenFilter(byte[] expectedSha256) {
        this.expectedSha256 = Objects.requireNonNull(expectedSha256).clone();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX) && matches(header.substring(PREFIX.length()))) {
            SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("service-token", null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            SecurityContextHolder.getContextHolderStrategy().setContext(context);
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String token) {
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(actual, expectedSha256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
