package ga.comm.outbox.publish;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * 소수(요율·지급률·배수)를 포털 계약의 불투명 소수 문자열로(포털 defs {@code decimalString} — {@code ^(0|[1-9][0-9]*)(\.[0-9]{1,6})?$}).
 * 표시 전용이며 반올림하지 않는다 — 소수 6자리를 넘거나 음수면 계약에 담을 수 없으므로 실패한다(fail-fast, 부록 B-13).
 */
public final class Decimals {

    private static final Pattern CONTRACT = Pattern.compile("^(0|[1-9][0-9]*)(\\.[0-9]{1,6})?$");

    private Decimals() {
    }

    public static String plain(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() < 0) {
            v = v.setScale(0);
        }
        String s = v.toPlainString();
        if (!CONTRACT.matcher(s).matches()) {
            throw new IllegalStateException("포털 계약의 소수 문자열로 표현할 수 없다(음수 또는 소수 6자리 초과): " + value.toPlainString());
        }
        return s;
    }

    public static String plain(String decimal) {
        return plain(new BigDecimal(decimal));
    }
}
