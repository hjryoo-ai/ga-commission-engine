plugins {
    `java-library`
}

dependencies {
    // 섀도 런은 자체 계산(COMM_CALC 순액)과 외부 정산 결과를 대조한다 —
    // NetAmountCalculator·CommCalcStore를 보려면 commission-calc가 필요하다.
    api(project(":commission-calc"))

    testImplementation(testFixtures(project(":commission-calc")))
}
