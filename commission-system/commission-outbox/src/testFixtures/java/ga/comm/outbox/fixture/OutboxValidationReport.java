package ga.comm.outbox.fixture;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 시험 태스크 끝에 전역 검증기 집계를 {@code build/test-results/outbox-validation/<태스크>.xml}로 쓴다(Phase E1 C1 — CI 산출물
 * {@code test-reports}에 담긴다). 경로는 Gradle이 시스템 속성 {@code outbox.validation.file}로 준다. 발행이 없는 태스크는 쓰지 않는다.
 * {@code META-INF/services}로 등록된다(시험 픽스처 jar를 쓰는 모듈 전부).
 */
public final class OutboxValidationReport implements TestExecutionListener {

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        String file = System.getProperty("outbox.validation.file");
        if (file == null || (OutboxValidation.total() == 0 && OutboxValidation.violationsByType().isEmpty())) {
            return;
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        int violations = OutboxValidation.violationsByType().values().stream().mapToInt(Integer::intValue).sum();
        xml.append("<outboxValidation validated=\"").append(OutboxValidation.total())
                .append("\" violations=\"").append(violations).append("\">\n");
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(OutboxValidation.validatedByType()).entrySet()) {
            xml.append("  <type name=\"").append(e.getKey()).append("\" validated=\"").append(e.getValue()).append("\"/>\n");
        }
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(OutboxValidation.violationsByType()).entrySet()) {
            xml.append("  <violation type=\"").append(e.getKey()).append("\" count=\"").append(e.getValue()).append("\"/>\n");
        }
        xml.append("  <agentIds count=\"").append(OutboxValidation.agentIds().size()).append("\"/>\n");
        xml.append("</outboxValidation>\n");
        try {
            Path path = Path.of(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, xml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
