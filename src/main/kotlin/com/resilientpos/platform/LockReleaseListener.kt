package com.resilientpos.platform

import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.scheduling.concurrent.CustomizableThreadFactory
import org.springframework.stereotype.Component

/**
 * 잠금 해제 알림([RedisDistributedLock.RELEASED_CHANNEL])을 구독해 [RedisDistributedLock]에서 기다리는 요청을 바로 깨운다.
 * 구독이 없어도 락은 재시도 간격으로 기다리므로 이 구독은 기다림을 줄일 뿐이다.
 *
 * 기동이 끝난 뒤에 구독한다. Spring Data Redis 4.1.1 `RedisMessageListenerContainer.start`는 구독이 2초 안에 되지 않으면 던져서,
 * 기동 단계에서 시작하면 Redis 없이 앱이 뜨지 않는다. 그 컨테이너는 첫 구독에서 연결하지 못하면 스스로 다시 시도하지 않으므로
 * (`Subscriber.initialize`가 첫 시도의 연결 실패를 그대로 돌려준다) 실패하면 WARN을 남기고 [RESUBSCRIBE_INTERVAL] 뒤 다시 구독한다.
 * 컨테이너가 연결 뒤의 실패를 다시 시도할 때도 [recovery]를 쓴다. 그 실행기가 `ScheduledExecutorService`가 아니면 부른 스레드를 재운다.
 * 받은 메시지는 기다리는 요청을 깨우기만 하므로 수신 스레드에서 바로 처리한다. 기본 실행기는 메시지마다 스레드를 만든다.
 */
@Component
class LockReleaseListener(connectionFactory: RedisConnectionFactory, lock: RedisDistributedLock) :
    ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** [destroy]가 세운다. 예약된 다시 구독이 종료 뒤에 구독을 열지 않게 한다. */
    private val closed = AtomicBoolean()

    private val recovery = ScheduledThreadPoolExecutor(1, CustomizableThreadFactory("lock-release-").apply { setDaemon(true) })

    private val container = RedisMessageListenerContainer().apply {
        setConnectionFactory(connectionFactory)
        setTaskExecutor(SyncTaskExecutor())
        setSubscriptionExecutor(recovery)
        addMessageListener(
            MessageListener { message, _ -> lock.onReleased(String(message.body, Charsets.UTF_8)) },
            ChannelTopic(RedisDistributedLock.RELEASED_CHANNEL),
        )
        afterPropertiesSet()
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) = subscribe()

    private fun subscribe() {
        if (closed.get()) return
        try {
            container.start()
        } catch (e: RuntimeException) {
            if (closed.get()) return
            log.warn { "잠금 해제 알림을 구독하지 못했다(${e.message}). 락은 재시도 간격으로만 기다리고 ${RESUBSCRIBE_INTERVAL.toSeconds()}초 뒤 다시 구독한다" }
            container.stop()
            try {
                recovery.schedule({ subscribe() }, RESUBSCRIBE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                // 위의 확인 뒤 destroy가 실행기를 닫았다. 종료 중이라 다시 구독하지 않는다.
            }
        }
    }

    override fun destroy() {
        closed.set(true)
        recovery.shutdownNow()
        container.destroy()
    }

    private companion object {
        private val log by logger()

        /** 컨테이너의 연결 뒤 재시도 간격(`FixedBackOff` 기본 5초)과 같다. */
        private val RESUBSCRIBE_INTERVAL: Duration = Duration.ofSeconds(5)
    }
}
