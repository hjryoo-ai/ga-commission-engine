rootProject.name = "commission-system"

include(
    "commission-domain",
    "commission-rule",
    "commission-calc",
    "commission-limit",
    "commission-deferral",
    "commission-clawback",
    "commission-inbound",
    "commission-recon",
    "commission-shadow",
    "commission-settlement",
    "commission-outbox",
    "commission-batch",
    "commission-disclosure",
    "commission-api",
    "commission-infra",
    "commission-app",
    "commission-localstack",   // E2 ⑤ 로컬 스택 시나리오 실행기(운영 jar 밖)
)
