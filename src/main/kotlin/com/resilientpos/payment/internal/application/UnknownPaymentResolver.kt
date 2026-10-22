package com.resilientpos.payment.internal.application

import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.platform.Lock
import com.resilientpos.platform.logger
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 결과를 모른 채 남은(`UNKNOWN`) 승인 시도를 주기적으로 provider에 다시 물어 확정한다. 한 건의 확정은 [PaymentManagement.resolveUnknown]이 한다.
 *
 * 모든 pod에서 돌고 [Lock]이 pod 사이를 줄 세운다. 락은 중복 조회를 줄이는 완화 수단이고 정합성은 Facade가 행을 잠근 뒤 `UNKNOWN`을 다시 확인하는 데서 나온다.
 * 락이 fail-open되거나 블록이 TTL을 넘겨 다른 pod가 겹쳐 들어와도(배치가 락 대기 한도(1.5초)를 넘기면 늦게 온 pod는 fail-open으로 함께 돈다) 조회는 읽기라 해가 없다.
 *
 * `Lock` KDoc은 블록 안에서 행 잠금을 기다리지 않도록 정하지만 여기는 예외다. 블록 안에서 `resolveUnknown`이 `UNKNOWN` 시도 행을 잠근다.
 * 그 행을 잠그는 다른 경로(`authorize`의 적용, `retryAuthorization`)는 이 락 키를 잡지 않아 락과 행 잠금이 서로를 기다리는 순환이 생기지 않는다.
 *
 * 한 건이 던지면(잠금 대기 초과, DB 오류) ERROR로 남기고 다음 건으로 간다. 그 건은 `UNKNOWN`으로 남아 다음 주기에 다시 잡힌다.
 * 대상 조회가 던지면 그 주기를 건너뛰고 Spring 스케줄러가 로그를 남긴다.
 *
 * 영영 불명인 건이 새 건을 막지 않게 지난 주기에 읽은 마지막 `id` 다음부터 읽는다(keyset). 끝에 닿으면(읽은 건이 [BATCH_SIZE]보다 적으면) 다음 주기는 처음부터다.
 * 새 건은 늦어도 ceil(불명 건수 / [BATCH_SIZE]) 주기 안에 조회된다. 커서는 pod마다 따로이고 재기동하면 처음부터라 조회가 겹치거나 늦어질 뿐 정합성에는 닿지 않는다.
 * 스케줄러는 `Lock` 블록 안에서 줄 서므로 한 인스턴스의 커서가 동시에 바뀌지 않지만 테스트가 직접 부를 수 있어 `@Volatile`이다.
 *
 * 대상 조회는 reconciliation으로 넘긴 시도를 고르지 않는다. 넘긴 시도는 `payment`가 더 조회하지 않고 reconciliation의 결론으로만 확정된다.
 *
 * 결과 불명 시도 수는 gauge `payment.attempt.unknown`(넘긴 시도 포함), 넘긴 뒤 아직 불명인 시도 수는 `payment.attempt.reconciliation.pending`으로 낸다.
 * 한 주기의 조회·넘김이 끝난 뒤(락 밖)에 세어 갱신하므로 scrape가 DB를 부르지 않고, 그 주기에 확정하거나 넘긴 시도가 같은 주기의 값에 반영된다. 세기가 던지면 값은 마지막 성공값으로 남는다.
 */
@Component
class UnknownPaymentResolver(
    private val repository: PaymentAttemptRepository,
    private val paymentManagement: PaymentManagement,
    meterRegistry: MeterRegistry,
) {
    private val unknownCount = AtomicLong(0)
    private val reconciliationPendingCount = AtomicLong(0)

    @Volatile
    private var cursor: PaymentId? = null

    init {
        Gauge.builder("payment.attempt.unknown", unknownCount) { it.toDouble() }
            .description("결과 불명 승인 시도 수, 작업 주기마다 갱신")
            .register(meterRegistry)
        Gauge.builder("payment.attempt.reconciliation.pending", reconciliationPendingCount) { it.toDouble() }
            .description("reconciliation으로 넘긴 뒤 아직 결과 불명인 승인 시도 수, 작업 주기마다 갱신")
            .register(meterRegistry)
    }

    @Scheduled(
        fixedDelayString = "\${resilientpos.payment.unknown-resolve-interval}",
        initialDelayString = "\${resilientpos.payment.unknown-resolve-interval}",
    )
    fun resolveUnknown() {
        Lock.executeWithLockNoTx(LOCK_KEY) {
            val ids = repository.findUnknownAuthorizationIds(cursor, BATCH_SIZE)
            ids.forEach { resolveOne(it) }
            cursor = if (ids.size < BATCH_SIZE) null else ids.last()
        }
        unknownCount.set(repository.countUnknownAuthorizations())
        reconciliationPendingCount.set(repository.countReconciliationPendingAuthorizations())
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
