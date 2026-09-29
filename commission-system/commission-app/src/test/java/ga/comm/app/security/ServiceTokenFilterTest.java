package ga.comm.app.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 서비스 토큰 필터와 인스턴스 구성의 fail-fast(Phase E3, 부록 B-13). 풀 체인 401/403은 BootSmokeIT. */
class ServiceTokenFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static byte[] sha(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean authenticatedWith(String header) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/x");
        if (header != null) {
            request.addHeader("Authorization", header);
        }
        new ServiceTokenFilter(sha("secret-token")).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SERVICE"));
    }

    @Test
    void 맞는_토큰만_SERVICE로_인증된다() throws Exception {
        assertThat(authenticatedWith("Bearer secret-token")).isTrue();
        assertThat(authenticatedWith("Bearer secret-token ")).isFalse();
        assertThat(authenticatedWith("Bearer other")).isFalse();
        assertThat(authenticatedWith("Basic c2VjcmV0LXRva2Vu")).isFalse();
        assertThat(authenticatedWith(null)).isFalse();
    }

    @Test
    void 인스턴스_구성은_기본값_없이_fail_fast() {
        InstanceProperties empty = new InstanceProperties();
        assertThatThrownBy(empty::requiredTenantId).isInstanceOf(IllegalStateException.class).hasMessageContaining("tenant-id");
        assertThatThrownBy(empty::requiredServiceTokenSha256).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service-token-sha256");

        InstanceProperties bad = new InstanceProperties();
        bad.setTenantId("t1");
        bad.getInternal().setServiceTokenSha256("abc");
        assertThatThrownBy(bad::requiredTenantId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(bad::requiredServiceTokenSha256).isInstanceOf(IllegalStateException.class);

        InstanceProperties ok = new InstanceProperties();
        ok.setTenantId("T1");
        ok.getInternal().setServiceTokenSha256("A".repeat(64));
        assertThat(ok.requiredTenantId()).isEqualTo("T1");
        assertThat(ok.requiredServiceTokenSha256()).hasSize(32);
    }
}
