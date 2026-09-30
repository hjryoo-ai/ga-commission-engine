package ga.comm.disclosure.grade;

/** 요청 tenantId ≠ 엔진 인스턴스 테넌트(403, TENANT_MISMATCH). 엔진은 테넌트별 인스턴스라 이 검사가 격리의 전부다. */
public class TenantMismatchException extends RuntimeException {

    public TenantMismatchException(String requested) {
        super("tenant " + requested + " is not served by this engine instance");
    }
}
