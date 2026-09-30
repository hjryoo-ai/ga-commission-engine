package ga.comm.infra.store;

import ga.comm.disclosure.grade.group.GroupMember;
import ga.comm.disclosure.grade.group.ProductGroupDirectory;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.infra.mapper.DisclosureGradeMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 유사상품군·소속 Oracle 어댑터(Phase E3). */
public class OracleProductGroupDirectory implements ProductGroupDirectory {

    private final DisclosureGradeMapper mapper;

    public OracleProductGroupDirectory(DisclosureGradeMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public boolean groupExists(String groupCodeSystem, String groupCode, LocalDate date) {
        return mapper.countGroups(groupCodeSystem, groupCode, Objects.requireNonNull(date)) > 0;
    }

    @Override
    public List<GroupMember> members(String groupCodeSystem, String groupCode, LocalDate date) {
        return mapper.members(groupCodeSystem, groupCode, Objects.requireNonNull(date)).stream()
                .map(r -> new GroupMember(r.extProductKey, new InsurerCode(r.insurerCd), new ProductKey(r.productKey))).toList();
    }
}
