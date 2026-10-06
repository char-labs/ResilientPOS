package com.resilientpos.payment.internal.application

import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.platform.Lock
import com.resilientpos.platform.logger
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 결과를 모른 채 남은(`UNKNOWN`) 승인 시도를 주기적으로 provider에 다시 물어 확정한다. 한 건의 확정은 [PaymentManagement.resolveUnknown]이 한다.
 *
 * 모든 pod에서 돌고 [Lock]이 pod 사이를 줄 세운다. 락은 중복 조회를 줄이는 완화 수단이고 정합성은 Facade가 행을 잠근 뒤 `UNKNOWN`을 다시 확인하는 데서 나온다.
 * 락이 fail-open되거나 블록이 TTL을 넘겨 다른 pod가 겹쳐 들어와도 조회는 읽기라 해가 없다.
 *
 * `Lock` KDoc은 블록 안에서 행 잠금을 기다리지 않도록 정하지만 여기는 예외다. 블록 안에서 `resolveUnknown`이 `UNKNOWN` 시도 행을 잠근다.
 * 그 행을 잠그는 다른 경로(`authorize`의 적용, `retryAuthorization`)는 이 락 키를 잡지 않아 락과 행 잠금이 서로를 기다리는 순환이 생기지 않는다.
 *
 * 한 건이 던지면(잠금 대기 초과, DB 오류) ERROR로 남기고 다음 건으로 간다. 그 건은 `UNKNOWN`으로 남아 다음 주기에 다시 잡힌다.
 * 대상 조회가 던지면 그 주기를 건너뛰고 Spring 스케줄러가 로그를 남긴다.
 *
 * ponytail: 조회로도 영영 확정되지 않는 건이 [BATCH_SIZE]를 넘게 쌓이면 그 뒤의 새 건이 밀린다. reconciliation case가 생기면 그쪽으로 넘겨 푼다.
 */
@Component
class UnknownPaymentResolver(
    private val repository: PaymentAttemptRepository,
    private val paymentManagement: PaymentManagement,
) {

    @Scheduled(
        fixedDelayString = "\${resilientpos.payment.unknown-resolve-interval}",
        initialDelayString = "\${resilientpos.payment.unknown-resolve-interval}",
    )
    fun resolveUnknown() = Lock.executeWithLockNoTx(LOCK_KEY) {
        repository.findUnknownAuthorizationIds(BATCH_SIZE).forEach { resolveOne(it) }
    }

    private fun resolveOne(paymentId: PaymentId) {
        try {
            paymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))
        } catch (e: Exception) {
            log.error(e) { "결과 불명 결제 확정에 실패했다 paymentId=${paymentId.value}" }
        }
    }

    companion object {
        const val LOCK_KEY = "payment:unknown-resolve"

        /** 한 주기에 확정을 시도하는 최대 건수. */
        private const val BATCH_SIZE = 100

        private val log by logger()
    }
}
