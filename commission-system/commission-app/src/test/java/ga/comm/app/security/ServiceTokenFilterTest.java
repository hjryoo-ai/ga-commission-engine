package ga.comm.app.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

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
        new ServiceTokenFilter(List.of(sha("secret-token"))).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
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
        assertThatThrownBy(empty::requiredServiceTokenSha256s).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service-token-sha256");

        InstanceProperties bad = new InstanceProperties();
        bad.setTenantId("t1");
        bad.getInternal().setServiceTokenSha256(List.of("abc"));
        assertThatThrownBy(bad::requiredTenantId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(bad::requiredServiceTokenSha256s).isInstanceOf(IllegalStateException.class);

        InstanceProperties ok = new InstanceProperties();
        ok.setTenantId("T1");
        ok.getInternal().setServiceTokenSha256(List.of("A".repeat(64)));
        assertThat(ok.requiredTenantId()).isEqualTo("T1");
        assertThat(ok.requiredServiceTokenSha256s()).singleElement().satisfies(h -> assertThat(h).hasSize(32));
    }

    /** E3.1(E3 수용 심사 §4-4): 회전 겹침 기간 — 현재·다음 두 해시를 모두 인정하고, 목록 밖 토큰은 거부. */
    @Test
    void 회전_겹침_기간에는_현재와_다음_토큰을_모두_인정한다() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(List.of(sha("current-token"), sha("next-token")));
        for (String token : List.of("current-token", "next-token")) {
            assertThat(authenticatedWith(filter, "Bearer " + token)).as(token).isTrue();
        }
        assertThat(authenticatedWith(filter, "Bearer retired-token")).isFalse();
        assertThat(authenticatedWith(filter, null)).isFalse();
        assertThatThrownBy(() -> new ServiceTokenFilter(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    /** 설정은 쉼표 구분 목록으로 묶이고(환경변수 한 줄), 1~2개·64자리 16진·중복 없음이 아니면 기동 실패(B-13). */
    @Test
    void 해시_목록_설정은_1개_또는_2개만_받는다() throws Exception {
        String current = HexFormat.of().formatHex(sha("current-token"));
        String next = HexFormat.of().formatHex(sha("next-token"));
        assertThat(bind(current + "," + next).requiredServiceTokenSha256s()).hasSize(2);
        assertThat(bind(current).requiredServiceTokenSha256s()).hasSize(1);
        assertThatThrownBy(() -> bind(current + "," + next + "," + "b".repeat(64)).requiredServiceTokenSha256s())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("3개");
        assertThatThrownBy(() -> bind(current + "," + current.toUpperCase()).requiredServiceTokenSha256s())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("두 번");
        assertThatThrownBy(() -> bind(current + ",xyz").requiredServiceTokenSha256s()).isInstanceOf(IllegalStateException.class);
    }

    private static InstanceProperties bind(String hashes) {
        return new Binder(new MapConfigurationPropertySource(Map.of("app.tenant-id", "T1", "app.internal.service-token-sha256", hashes)))
                .bind("app", InstanceProperties.class).get();
    }

    private static boolean authenticatedWith(ServiceTokenFilter filter, String header) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/x");
        if (header != null) {
            request.addHeader("Authorization", header);
        }
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SERVICE"));
    }
}
