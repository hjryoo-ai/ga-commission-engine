package ga.comm.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 엔진 인스턴스 구성(Phase E3). 엔진은 테넌트별 인스턴스다 — {@code app.tenant-id}가 이 인스턴스가 서비스하는 유일한 테넌트이고,
 * 내부 API({@code /internal/**})는 서비스 토큰으로만 호출된다. 저장소에는 토큰이 없다: 설정에는 토큰의 SHA-256(16진)만 두고
 * 원문은 호출자(ga-disclosure)의 비밀 저장소에 있다. 둘 다 기본값이 없고, 비었거나 형식이 틀리면 <b>기동 실패</b>(부록 B-13).
 *
 * <p>토큰 회전(E3 수용 심사 §4-4, E3.1): 해시는 <b>목록</b>이다(쉼표 구분 — {@code ENGINE_SERVICE_TOKEN_SHA256=현재,다음}).
 * 겹침 기간에는 현재·다음 두 토큰을 모두 인정하고, 회전이 끝나면 옛 해시를 뺀다. 1개 또는 2개, 서로 달라야 한다.
 */
@ConfigurationProperties(prefix = "app")
public class InstanceProperties {

    private static final Pattern TENANT = Pattern.compile("^[A-Z0-9][A-Z0-9_]{0,31}$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    private String tenantId;
    private final Internal internal = new Internal();

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Internal getInternal() {
        return internal;
    }

    public static class Internal {
        private List<String> serviceTokenSha256 = new ArrayList<>();

        public List<String> getServiceTokenSha256() {
            return serviceTokenSha256;
        }

        public void setServiceTokenSha256(List<String> serviceTokenSha256) {
            this.serviceTokenSha256 = serviceTokenSha256;
        }
    }

    /** 검증된 인스턴스 테넌트. */
    public String requiredTenantId() {
        if (tenantId == null || !TENANT.matcher(tenantId).matches()) {
            throw new IllegalStateException("app.tenant-id(ENGINE_TENANT_ID)가 없거나 형식이 틀립니다 — 엔진은 테넌트별 인스턴스이며 "
                    + "기본값이 없습니다(부록 B-13)");
        }
        return tenantId;
    }

    /** 회전 겹침 기간에 함께 인정하는 해시 수의 상한(현재·다음). */
    static final int MAX_ACCEPTED_TOKENS = 2;

    /** 검증된 서비스 토큰 SHA-256 목록(바이트) — 1개 또는 2개, 중복 없음. */
    public List<byte[]> requiredServiceTokenSha256s() {
        List<String> hexes = internal.serviceTokenSha256 == null ? List.of() : internal.serviceTokenSha256;
        if (hexes.isEmpty() || hexes.size() > MAX_ACCEPTED_TOKENS) {
            throw new IllegalStateException("app.internal.service-token-sha256(ENGINE_SERVICE_TOKEN_SHA256)는 64자리 16진 해시 1개 또는 "
                    + MAX_ACCEPTED_TOKENS + "개(현재,다음)여야 합니다 — 지금 " + hexes.size() + "개. 내부 API를 인증 없이 열 수 없습니다(부록 B-13)");
        }
        Set<String> seen = new HashSet<>();
        List<byte[]> hashes = new ArrayList<>();
        for (String hex : hexes) {
            if (hex == null || !SHA256_HEX.matcher(hex.trim()).matches()) {
                throw new IllegalStateException("app.internal.service-token-sha256(ENGINE_SERVICE_TOKEN_SHA256)의 항목이 64자리 16진이 "
                        + "아닙니다 — 내부 API를 인증 없이 열 수 없습니다(부록 B-13)");
            }
            String normalized = hex.trim().toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                throw new IllegalStateException("app.internal.service-token-sha256에 같은 해시가 두 번 있습니다(부록 B-13)");
            }
            hashes.add(HexFormat.of().parseHex(normalized));
        }
        return List.copyOf(hashes);
    }
}
