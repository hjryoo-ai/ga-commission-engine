package ga.comm.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 엔진 인스턴스 구성(Phase E3). 엔진은 테넌트별 인스턴스다 — {@code app.tenant-id}가 이 인스턴스가 서비스하는 유일한 테넌트이고,
 * 내부 API({@code /internal/**})는 서비스 토큰으로만 호출된다. 저장소에는 토큰이 없다: 설정에는 토큰의 SHA-256(16진)만 두고
 * 원문은 호출자(ga-disclosure)의 비밀 저장소에 있다. 둘 다 기본값이 없고, 비었거나 형식이 틀리면 <b>기동 실패</b>(부록 B-13).
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
        private String serviceTokenSha256;

        public String getServiceTokenSha256() {
            return serviceTokenSha256;
        }

        public void setServiceTokenSha256(String serviceTokenSha256) {
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

    /** 검증된 서비스 토큰 SHA-256(바이트). */
    public byte[] requiredServiceTokenSha256() {
        String hex = internal.serviceTokenSha256;
        if (hex == null || !SHA256_HEX.matcher(hex).matches()) {
            throw new IllegalStateException("app.internal.service-token-sha256(ENGINE_SERVICE_TOKEN_SHA256)가 없거나 64자리 16진이 "
                    + "아닙니다 — 내부 API를 인증 없이 열 수 없습니다(부록 B-13)");
        }
        return HexFormat.of().parseHex(hex.toLowerCase(Locale.ROOT));
    }
}
