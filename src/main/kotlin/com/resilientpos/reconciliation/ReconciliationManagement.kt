package com.resilientpos.reconciliation

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.payment.PaymentAttemptStatus
import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.platform.Tx
import com.resilientpos.platform.logger
import com.resilientpos.reconciliation.internal.domain.ReconciliationCase
import com.resilientpos.reconciliation.internal.domain.ReconciliationCaseRepository
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import org.springframework.stereotype.Service

/**
 * `reconciliation` 모듈의 유일한 쓰기 진입점. case를 열고 점주의 결론으로 해소한다.
 * 인터페이스를 따로 두지 않는 구체 클래스다. 구현이 둘이 될 때 뽑는다.
 */
@Service
class ReconciliationManagement(
    private val repository: ReconciliationCaseRepository,
    private val paymentManagement: PaymentManagement,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {

    /**
     * 넘겨받은 시도로 case를 연다. 같은 시도의 case가 이미 있으면(재전달) 그것을 돌려주고 아무것도 쓰지 않는다.
     *
     * 같은 사실이 겹쳐 오면 둘 다 "없음"을 읽고 둘 다 넣으려 하고, unique `payment_id`가 두 번째를 막는다. 두 번째는 커밋에서
     * `DataIntegrityViolationException`으로 실패하고 consumer의 Kafka 재시도가 다시 처리해 기존 case를 찾는다. 별도 잠금을 두지 않는다.
     * counter와 INFO 로그는 새로 열었을 때만, 트랜잭션이 반환된(커밋된) 뒤에 남긴다.
     */
    fun open(command: ReconciliationCommand.Open): ReconciliationCommand.Open.Result {
        val (result, opened) = Tx.masterTx {
            repository.findByPaymentId(command.paymentId)?.let {
                return@masterTx ReconciliationCommand.Open.Result(it.id, it.status) to null
            }
            val saved = repository.save(ReconciliationCase.open(command, identifierGenerator).aggregate)
            ReconciliationCommand.Open.Result(saved.id, saved.status) to saved
        }
        if (opened != null) {
            meterRegistry.counter("reconciliation.case.opened", "reason", opened.reason.name).increment()
            log.info {
                "대조 case를 열었다 caseId=${opened.id.value} paymentId=${opened.paymentId.value} storeId=${opened.storeId.value} " +
                    "reason=${opened.reason} status=${opened.status} ruleVersion=${opened.ruleVersion}"
            }
        }
        return result
    }

    /**
     * 점주의 결론으로 case를 해소하고 같은 트랜잭션에서 결제 시도에 그 결론을 적용한다(도메인 모델 §4.6, `reconciliation → payment`의 합류 호출).
     * 이 메서드가 `Tx.masterTx`를 열고, [PaymentManagement.resolveFromReconciliation]은 `REQUIRED`로 합류한다. 결제 확정과 case 해소가 한 커밋이다.
     * 잠금 순서는 case 행 다음 시도 행이다. 시도 행을 잠그는 다른 경로(조회 확정·승인 적용·재결제)는 case 행을 잠그지 않아 순환 대기가 없다.
     *
     * 이미 같은 결론으로 해소된 case면 payment를 부르지 않고 기존 결과를 돌려준다(응답을 잃은 재요청, 동시 요청의 늦은 쪽). counter와 감사 INFO 로그는 처음 해소했을 때만, 트랜잭션이 반환된(커밋된) 뒤에 남긴다.
     * 시도가 결론과 다른 결말이면 던져서 둘 다 롤백한다. 넘긴 시도는 이 경로로만 확정되므로 정상 경로에서는 오지 않고, 롤링 배포 중 이전 버전의 주기 작업이
     * 넘긴 시도를 조회로 확정한 경우에만 만난다. 점주는 메시지의 결제 결말과 같은 결론으로 다시 해소한다.
     */
    fun resolve(command: ReconciliationCommand.Resolve): ReconciliationCommand.Resolve.Result {
        val (result, firstResolved) = Tx.masterTx { resolveInTransaction(command) }
        if (firstResolved) {
            meterRegistry.counter("reconciliation.case.resolved", "decision", command.decision.name).increment()
            // 사유 원문은 자유 입력이라 싣지 않는다(DB의 해소 기록이 정본이다).
            log.info {
                "audit action=RECONCILIATION_CASE_RESOLVED ownerId=${command.ownerId.value} storeId=${command.storeId.value} " +
                    "caseId=${result.caseId.value} paymentId=${result.paymentId.value} decision=${result.decision} paymentStatus=${result.paymentStatus}"
            }
        }
        return result
    }

    private fun resolveInTransaction(command: ReconciliationCommand.Resolve): Pair<ReconciliationCommand.Resolve.Result, Boolean> {
        val current = repository.findByIdAndStoreIdForUpdate(command.caseId, command.storeId) ?: throw ReconciliationCase.notFound()
        val wasResolved = current.status == ReconciliationCaseStatus.RESOLVED
        val resolved = current.resolve(command, clock.instant()).aggregate
        val expectedStatus = when (command.decision) {
            ReconciliationDecision.APPROVED -> PaymentAttemptStatus.APPROVED
            ReconciliationDecision.DECLINED -> PaymentAttemptStatus.DECLINED
        }
        if (wasResolved) {
            return resultOf(resolved, command.decision, expectedStatus) to false
        }

        val applied = paymentManagement.resolveFromReconciliation(
            PaymentCommand.ResolveFromReconciliation(
                resolved.paymentId,
                when (command.decision) {
                    ReconciliationDecision.APPROVED -> PaymentCommand.ResolveFromReconciliation.Conclusion.APPROVED
                    ReconciliationDecision.DECLINED -> PaymentCommand.ResolveFromReconciliation.Conclusion.DECLINED
                },
                // 정규화된 참조는 해소 기록의 것이다(공백뿐인 참조는 null).
                checkNotNull(resolved.resolution) { "해소한 case는 해소 기록을 가진다 caseId=${resolved.id.value}" }.providerReference,
            ),
        )
        if (applied.status != expectedStatus) {
            throw DomainException(
                ReconciliationCase.RECONCILIATION_PAYMENT_ALREADY_CONCLUDED,
                "결제 시도가 이미 ${applied.status} 결말이라 ${command.decision}로 해소할 수 없습니다. 결제 결말과 같은 결론으로 다시 해소하세요 caseId=${current.id.value}",
                DomainErrorKind.CONFLICT,
            )
        }
        return resultOf(repository.save(resolved), command.decision, applied.status) to true
    }

    private fun resultOf(case: ReconciliationCase, decision: ReconciliationDecision, paymentStatus: PaymentAttemptStatus) =
        ReconciliationCommand.Resolve.Result(case.id, case.status, decision, case.paymentId, paymentStatus)

    companion object {
        private val log by logger()
    }
}
