package com.resilientpos.platform

import com.resilientpos.foundation.DomainException
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.TopicConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.RetryListener
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries
import org.springframework.orm.jpa.JpaSystemException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.util.backoff.ExponentialBackOff

/**
 * 모듈 사이 이벤트를 나르는 토픽과 소비 실패 처리. 이벤트 타입과 consumer는 각 모듈에 있고 여기는 브로커 쪽 구성만 둔다.
 *
 * 토픽 이름은 설정의 목록에서 읽는다. 이벤트 클래스의 `TOPIC` 상수를 여기서 참조하면 `platform`이 업무 모듈을 의존해
 * `payment → platform`과 순환한다. `@KafkaListener`가 듣는 토픽이 목록에 없으면 `KafkaEventDeliveryIntegrationTest`가 잡는다.
 */
@Configuration
class KafkaConfiguration {

    /**
     * 토픽마다 DLT(`이름.DLT`)를 만든다. DLT는 보존 기간을 두지 않는다. 불일치 승인처럼 사람이 볼 때까지 남아야 하는
     * case 대신이라 브로커 기본값(7일)에 지워지면 안 된다.
     *
     * 파티션 수를 설정보다 크게 바꾸면 `KafkaAdmin`이 기동하면서 늘린다. 그 순간 key 배치가 바뀌어 같은 주문의 순서가
     * 깨질 수 있으므로 환경 변수로 열지 않는다. 늘리는 PR이 consumer를 비운 뒤 설정을 바꾼다.
     *
     * 복제 수는 설정이 없으면 지정하지 않아 브로커의 `default.replication.factor`를 따른다. 앱 기본값을 1로 두면 운영에서
     * 설정을 빠뜨렸을 때 복제 1인 토픽이 생기고, `KafkaAdmin`은 복제 수를 바꾸지 않아 되돌리기 어렵다.
     */
    @Bean
    fun eventTopics(
        @Value("\${resilientpos.kafka.topics}") topics: List<String>,
        @Value("\${resilientpos.kafka.partitions}") partitions: Int,
        @Value("\${resilientpos.kafka.replicas:#{null}}") replicas: Int?,
    ): KafkaAdmin.NewTopics {
        fun builder(name: String) = TopicBuilder.name(name).partitions(partitions).apply { replicas?.let(::replicas) }
        return KafkaAdmin.NewTopics(
            *topics.flatMap { topic ->
                listOf(
                    builder(topic).build(),
                    builder(topic + DLT_SUFFIX).config(TopicConfig.RETENTION_MS_CONFIG, "-1").build(),
                )
            }.toTypedArray(),
        )
    }

    /**
     * 실패한 메시지를 같은 자리에서 다시 시도한다(blocking). 대기는 consumer 스레드가 잠드는 방식이라 그 스레드가 맡은
     * 파티션이 모두 멈춘다. 그래서 `spring.kafka.listener.concurrency`를 파티션 수와 같게 둔다.
     *
     * 재시도는 허용 목록이다. 목록에 없는 예외(업무 규칙 위반 `DomainException`, 변환 실패, 버그)는 다시 해도 같은 결과라
     * 바로 DLT로 간다. 특히 provider를 부르는 consumer가 모르는 예외로 승인을 거듭 요청하지 않는다.
     * - 경합(잠금 대기 초과, 쿼리 타임아웃, 트랜잭션 시간 초과, unique 경합): 시작 간격에서 2배씩 `max-retries`번(기본 1초·5회, 약 31초).
     *   처리 도중 DB 연결이 끊긴 경우도 여기서 시작한다. 원래 예외(SQLState 57P01)가 롤백 실패에 덮여
     *   `JpaSystemException`·`TransactionSystemException`으로 오기 때문이다(2026-09-27 `pg_terminate_backend`로 실측).
     *   다음 시도에서 DB가 아직 없으면 커넥션 획득이 실패해 아래 기다림으로 넘어간다(예외가 바뀌면 재시도 상태가 새로 시작된다).
     *   대가로 JPA 계열의 버그성 예외도 5회 재시도된다. consumer가 멱등이라 감수한다.
     * - DB에 닿지 못함(커넥션 획득 실패, 연결 끊김): 상한 없이 30초 간격까지 늘려 기다린다. DB가 돌아오면 그대로 이어지고,
     *   장애 동안 들어온 메시지가 DLT에 쌓여 사람이 되돌려야 하는 일이 없다.
     */
    @Bean
    fun kafkaErrorHandler(
        template: KafkaOperations<Any, Any>,
        meterRegistry: MeterRegistry,
        @Value("\${resilientpos.kafka.topics}") topics: List<String>,
        @Value("\${resilientpos.kafka.retry.initial-interval}") initialInterval: Duration,
        @Value("\${resilientpos.kafka.retry.max-retries}") maxRetries: Int,
    ): CommonErrorHandler {
        // DLT로 간 적이 없어도 0으로 나가야 알림의 increase()가 첫 증가를 잡는다. 도메인 코드는 미리 알 수 없어 error만 등록한다.
        topics.forEach { meterRegistry.counter(DEAD_LETTER_METRIC, "topic", it, "reason", "error") }
        // 목적지를 명시한다. Spring Kafka 4의 기본 이름은 `원토픽-dlt`라 만들어 둔 토픽과 어긋난다.
        // 파티션을 비워 두면(-1) key로 나눈다. 같은 주문은 DLT에서도 한 파티션에 모이고 DLT 파티션 수에 묶이지 않는다.
        val deadLetter = DeadLetterPublishingRecoverer(template) { record, _ -> TopicPartition(record.topic() + DLT_SUFFIX, -1) }
        val contention = ExponentialBackOffWithMaxRetries(maxRetries).apply {
            this.initialInterval = initialInterval.toMillis()
            multiplier = 2.0
        }
        val unreachable = ExponentialBackOff(initialInterval.toMillis(), 2.0).apply { maxInterval = 30_000 }
        return DefaultErrorHandler({ record, exception ->
            deadLetter.accept(record, exception)
            // 업무 불일치(도메인 코드)와 그 밖의 실패를 나눠 센다. 대응하는 사람이 다르다.
            val reason = exception.causes().filterIsInstance<DomainException>().firstOrNull()?.code ?: "error"
            meterRegistry.counter(DEAD_LETTER_METRIC, "topic", record.topic(), "reason", reason).increment()
            log.error(exception) {
                "DLT로 보냄 topic=${record.topic()} partition=${record.partition()} offset=${record.offset()} " +
                    "key=${record.key()} reason=$reason exception=${(exception.cause ?: exception).javaClass.name}"
            }
        }, contention).apply {
            defaultFalse()
            addRetryableExceptions(
                TransientDataAccessException::class.java,
                DataIntegrityViolationException::class.java,
                TransactionTimedOutException::class.java,
                JpaSystemException::class.java,
                TransactionSystemException::class.java,
                CannotCreateTransactionException::class.java,
                DataAccessResourceFailureException::class.java,
            )
            setBackOffFunction { _, exception ->
                unreachable.takeIf {
                    exception.causes().any { it is CannotCreateTransactionException || it is DataAccessResourceFailureException }
                }
            }
            setRetryListeners(retryLogging(meterRegistry))
        }
    }

    /**
     * 실패마다 WARN 한 줄을 남긴다. DLT 전송까지 실패하면 재시도 상태가 처음으로 돌아가(`resetStateOnRecoveryFailure` 기본값)
     * 같은 메시지를 다시 처리하므로, 목록 밖 예외라도 리스너가 다시 불린다. 그 사실이 조용히 지나가지 않게 ERROR와 counter를 남긴다.
     */
    private fun retryLogging(meterRegistry: MeterRegistry) = object : RetryListener {
        override fun failedDelivery(record: ConsumerRecord<*, *>, ex: Exception?, deliveryAttempt: Int) {
            log.warn {
                "소비 실패 topic=${record.topic()} partition=${record.partition()} offset=${record.offset()} " +
                    "key=${record.key()} attempt=$deliveryAttempt exception=${(ex?.cause ?: ex)?.javaClass?.name}"
            }
        }

        override fun recoveryFailed(record: ConsumerRecord<*, *>, original: Exception?, failure: Exception) {
            meterRegistry.counter(DEAD_LETTER_FAILED_METRIC, "topic", record.topic()).increment()
            log.error(failure) {
                "DLT 전송 실패, 처음부터 다시 시도한다 topic=${record.topic()} partition=${record.partition()} " +
                    "offset=${record.offset()} key=${record.key()} original=${(original?.cause ?: original)?.javaClass?.name}"
            }
        }
    }

    /** 리스너 예외는 `ListenerExecutionFailedException`으로 감싸여 오고 JPA 예외는 한 번 더 감싸므로 원인 체인 전체를 본다. */
    private fun Throwable.causes() = generateSequence(this) { it.cause }

    companion object {
        private const val DLT_SUFFIX = ".DLT"

        /** DLT로 보낸 메시지 수. `topic`은 원래 토픽, `reason`은 도메인 오류 코드나 `error`다. 주문 식별자는 로그에만 남긴다. */
        const val DEAD_LETTER_METRIC = "event.kafka.dead-letter"

        /** DLT 전송 자체가 실패한 횟수. 0보다 크면 DLT(브로커·권한·크기)를 먼저 고친다. */
        const val DEAD_LETTER_FAILED_METRIC = "event.kafka.dead-letter.failed"

        private val log by logger()
    }
}
