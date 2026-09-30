package ga.comm.disclosure.grade.group;

import java.time.LocalDate;
import java.util.List;

/**
 * 유사상품군 코드 체계·소속 조회 포트(데이터 {@code DISC_PRODUCT_GROUP}·{@code DISC_PRODUCT_GROUP_MEMBER}).
 * 상품군 코드 체계는 외부 확정 사실(설계서 §11 #13, ga-disclosure §14 #1)이므로 코드에 코드값을 두지 않는다. 기준일 필수.
 */
public interface ProductGroupDirectory {

    boolean groupExists(String groupCodeSystem, String groupCode, LocalDate date);

    List<GroupMember> members(String groupCodeSystem, String groupCode, LocalDate date);
}
