package ga.comm.disclosure.grade.fixture;

import ga.comm.disclosure.grade.group.GroupMember;
import ga.comm.disclosure.grade.group.ProductGroupDirectory;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class InMemoryProductGroupDirectory implements ProductGroupDirectory {

    private record Group(String system, String code, LocalDate from, LocalDate to) {
    }

    private record Member(String system, String code, GroupMember member, LocalDate from, LocalDate to) {
    }

    private final List<Group> groups = new ArrayList<>();
    private final List<Member> members = new ArrayList<>();

    public InMemoryProductGroupDirectory group(String system, String code, LocalDate from) {
        groups.add(new Group(system, code, from, InMemoryDisclosurePolicyRepository.MAX_DATE));
        return this;
    }

    public InMemoryProductGroupDirectory member(String system, String code, String extKey, String insurer, String product,
                                                LocalDate from) {
        members.add(new Member(system, code, new GroupMember(extKey, new InsurerCode(insurer), new ProductKey(product)), from,
                InMemoryDisclosurePolicyRepository.MAX_DATE));
        return this;
    }

    @Override
    public boolean groupExists(String system, String code, LocalDate date) {
        return groups.stream().anyMatch(g -> g.system.equals(system) && g.code.equals(code)
                && !g.from.isAfter(date) && !g.to.isBefore(date));
    }

    @Override
    public List<GroupMember> members(String system, String code, LocalDate date) {
        return members.stream().filter(m -> m.system.equals(system) && m.code.equals(code)
                && !m.from.isAfter(date) && !m.to.isBefore(date)).map(Member::member).toList();
    }
}
