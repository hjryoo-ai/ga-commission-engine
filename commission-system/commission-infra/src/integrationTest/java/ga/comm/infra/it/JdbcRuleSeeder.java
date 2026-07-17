package ga.comm.infra.it;

import ga.comm.rule.contract.RuleSeeder;
import ga.comm.rule.model.ClawbackTable;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.DeferralCurve;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OrgOverrideRate;
import ga.comm.rule.model.PayoutRateRule;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;

/** 계약 테스트용 룰 시드 — 포트를 우회한 직접 SQL INSERT (시드의 순환 검증 방지). */
class JdbcRuleSeeder implements RuleSeeder {

    private final DataSource dataSource;

    JdbcRuleSeeder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void rate(CommRateRule rule) {
        execute("""
                INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type,
                    installment_no, rate, apply_from, apply_to, version_no, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setLong(1, rule.rateId());
            ps.setString(2, rule.direction().name());
            ps.setString(3, rule.insurerCd().value());
            ps.setString(4, rule.productKey().value());
            ps.setString(5, rule.commType().value());
            if (rule.installmentNo() == null) {
                ps.setNull(6, java.sql.Types.NUMERIC);
            } else {
                ps.setInt(6, rule.installmentNo());
            }
            ps.setBigDecimal(7, rule.rate().value());
            ps.setDate(8, Date.valueOf(rule.period().applyFrom()));
            ps.setDate(9, Date.valueOf(rule.period().applyTo()));
            ps.setLong(10, rule.versionNo());
            ps.setString(11, rule.status().name());
        });
    }

    @Override
    public void commTypeAttr(CommTypeAttr attr) {
        execute("""
                INSERT INTO COMM_TYPE_MST (comm_type, apply_from, apply_to, limit_included,
                    rounding_policy, clawback_target)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setString(1, attr.commType().value());
            ps.setDate(2, Date.valueOf(attr.period().applyFrom()));
            ps.setDate(3, Date.valueOf(attr.period().applyTo()));
            ps.setString(4, attr.limitIncluded() ? "Y" : "N");
            ps.setString(5, attr.roundingPolicy().name());
            ps.setString(6, attr.clawbackTarget() ? "Y" : "N");
        });
    }

    @Override
    public void payoutRate(PayoutRateRule rule) {
        execute("""
                INSERT INTO AGENT_PAYOUT_RATE (grade_cd, comm_type, payout_rate, apply_from, apply_to)
                VALUES (?, ?, ?, ?, ?)
                """, ps -> {
            ps.setString(1, rule.gradeCd());
            ps.setString(2, rule.commType().value());
            ps.setBigDecimal(3, rule.payoutRate().value());
            ps.setDate(4, Date.valueOf(rule.period().applyFrom()));
            ps.setDate(5, Date.valueOf(rule.period().applyTo()));
        });
    }

    @Override
    public void limitRule(LimitRule rule) {
        execute("""
                INSERT INTO LIMIT_RULE (rule_id, channel_type, limit_multiple, fy_window_months,
                    over_limit_action, clawback_restores, apply_from, apply_to)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setLong(1, rule.ruleId());
            ps.setString(2, rule.channelType().name());
            ps.setBigDecimal(3, rule.limitMultiple());
            ps.setInt(4, rule.fyWindowMonths());
            ps.setString(5, rule.overLimitAction().name());
            ps.setString(6, rule.clawbackRestores() ? "Y" : "N");
            ps.setDate(7, Date.valueOf(rule.period().applyFrom()));
            ps.setDate(8, Date.valueOf(rule.period().applyTo()));
        });
    }

    @Override
    public void deferralCurve(DeferralCurve curve) {
        execute("INSERT INTO DEFERRAL_CURVE (curve_id, curve_name, apply_from, apply_to) VALUES (?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, curve.curveId());
                    ps.setString(2, curve.curveName());
                    ps.setDate(3, Date.valueOf(curve.period().applyFrom()));
                    ps.setDate(4, Date.valueOf(curve.period().applyTo()));
                });
        for (DeferralCurve.CurvePoint point : curve.points()) {
            execute("INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (?, ?, ?)", ps -> {
                ps.setLong(1, curve.curveId());
                ps.setInt(2, point.monthNo());
                ps.setBigDecimal(3, point.pct().value());
            });
        }
    }

    @Override
    public void clawbackTable(ClawbackTable table) {
        for (ClawbackTable.Band band : table.bands()) {
            execute("""
                    INSERT INTO CLAWBACK_RULE (product_key, event_type, from_installment,
                        to_installment, clawback_pct, apply_from, apply_to)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, ps -> {
                ps.setString(1, table.productKey());
                ps.setString(2, table.eventType().name());
                ps.setInt(3, band.fromInstallment());
                ps.setInt(4, band.toInstallment());
                ps.setBigDecimal(5, band.clawbackPct().value());
                ps.setDate(6, Date.valueOf(table.period().applyFrom()));
                ps.setDate(7, Date.valueOf(table.period().applyTo()));
            });
        }
    }

    @Override
    public void orgOverrideRate(OrgOverrideRate rate) {
        execute("""
                INSERT INTO ORG_OVERRIDE_RATE (org_level, comm_type, override_rate, apply_from, apply_to)
                VALUES (?, ?, ?, ?, ?)
                """, ps -> {
            ps.setString(1, rate.orgLevel().name());
            ps.setString(2, rate.commType().value());
            ps.setBigDecimal(3, rate.overrideRate().value());
            ps.setDate(4, Date.valueOf(rate.period().applyFrom()));
            ps.setDate(5, Date.valueOf(rate.period().applyTo()));
        });
    }

    interface Binder {
        void bind(PreparedStatement ps) throws Exception;
    }

    static void execute(DataSource dataSource, String sql, Binder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("시드 INSERT 실패: " + sql, e);
        }
    }

    private void execute(String sql, Binder binder) {
        execute(dataSource, sql, binder);
    }
}
