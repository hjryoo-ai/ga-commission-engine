package ga.comm.disclosure.grade;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * POST 요청(계약 CommissionGradesRequest). {@link #of}가 형식을 검증한다 — 위반은 400
 * ({@link GradeRequestException}): 필수 누락, 패턴 위반, 빈 배열, 중복 productKey.
 */
public record GradeRequest(String tenantId, LocalDate asOfDate, String productGroupCode, List<Product> products) {

    public record Product(String productKey, String insurerCode) {
    }

    // 계약 패턴 그대로(engine-disclosure.openapi.yaml 1.1.0)
    private static final Pattern TENANT = Pattern.compile("^[A-Z0-9][A-Z0-9_]{0,31}$");
    private static final Pattern PRODUCT_KEY = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,63}:[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    public GradeRequest {
        products = List.copyOf(products);
    }

    public static GradeRequest of(String tenantId, LocalDate asOfDate, String productGroupCode, List<Product> products) {
        if (tenantId == null || !TENANT.matcher(tenantId).matches()) {
            throw GradeRequestException.invalid("tenantId must match " + TENANT.pattern());
        }
        if (asOfDate == null) {
            throw GradeRequestException.invalid("asOfDate is required");
        }
        if (productGroupCode == null || productGroupCode.isBlank()) {
            throw GradeRequestException.invalid("productGroupCode is required");
        }
        if (products == null || products.isEmpty()) {
            throw GradeRequestException.invalid("products must not be empty");
        }
        Set<String> seen = new HashSet<>();
        for (Product p : products) {
            if (p == null || p.productKey() == null || !PRODUCT_KEY.matcher(p.productKey()).matches()) {
                throw GradeRequestException.invalid("productKey must match " + PRODUCT_KEY.pattern());
            }
            if (p.insurerCode() == null || p.insurerCode().isBlank()) {
                throw GradeRequestException.invalid("insurerCode is required (" + p.productKey() + ")");
            }
            if (!seen.add(p.productKey())) {
                throw GradeRequestException.invalid("duplicate productKey " + p.productKey());
            }
        }
        return new GradeRequest(tenantId, asOfDate, productGroupCode, products);
    }
}
