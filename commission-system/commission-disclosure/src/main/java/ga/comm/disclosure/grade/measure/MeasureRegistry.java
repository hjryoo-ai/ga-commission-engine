package ga.comm.disclosure.grade.measure;

import ga.comm.disclosure.grade.policy.InvalidPolicyException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** measureKey → 측정 함수. 키 중복은 조립 시 실패, 미등록 키를 가리키는 정책은 정책 결함(fail-fast). */
public final class MeasureRegistry {

    private final Map<String, Measure> byKey;

    private MeasureRegistry(Map<String, Measure> byKey) {
        this.byKey = byKey;
    }

    public static MeasureRegistry of(List<Measure> measures) {
        Map<String, Measure> map = new LinkedHashMap<>();
        for (Measure m : measures) {
            if (map.putIfAbsent(m.key(), m) != null) {
                throw new IllegalStateException("duplicate measure key: " + m.key());
            }
        }
        return new MeasureRegistry(Map.copyOf(map));
    }

    public Measure get(String key) {
        Measure m = byKey.get(key);
        if (m == null) {
            throw new InvalidPolicyException(List.of("measureKey " + key + " is not registered " + byKey.keySet()));
        }
        return m;
    }
}
