package ga.comm.disclosure.grade.group;

import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;

import java.util.Objects;

/**
 * 유사상품군 소속 1건: 계약의 외부 {@code productKey}(최대 129자, {@code INSURER:PRODUCT}) → 엔진 요율 원장 키
 * {@code (insurer_cd, product_key)}. 외부 키를 쪼개 추측하지 않고 이 매핑 데이터로만 잇는다(계획 Q4).
 */
public record GroupMember(String extProductKey, InsurerCode insurerCd, ProductKey productKey) {

    public GroupMember {
        Objects.requireNonNull(extProductKey, "extProductKey");
        Objects.requireNonNull(insurerCd, "insurerCd");
        Objects.requireNonNull(productKey, "productKey");
    }
}
