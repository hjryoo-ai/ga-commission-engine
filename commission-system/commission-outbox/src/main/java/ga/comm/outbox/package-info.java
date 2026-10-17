/**
 * 아웃박스·이벤트 피드(Phase E1, 설계서 §6.8) — 엔진이 확정한 사실을 포털 계약(contracts/shared/ga-agent-portal)의 이벤트로
 * 업무 트랜잭션 안에서 적재하고, 순번 부여기가 커밋된 행에만 빈틈 없는 {@code seq}를 붙인다.
 */
package ga.comm.outbox;
