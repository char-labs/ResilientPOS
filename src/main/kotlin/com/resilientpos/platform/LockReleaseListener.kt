package com.resilientpos.platform

import java.util.concurrent.ScheduledThreadPoolExecutor
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
 * 기동 단계에서 시작하면 Redis 없이 앱이 뜨지 않는다. 실패하면 WARN을 남기고 컨테이너가 5초마다 다시 구독한다(그때마다 ERROR가 남는다).
 * 다시 구독하는 일은 [recovery]에서 돈다. 그 실행기가 `ScheduledExecutorService`가 아니면 컨테이너가 부른 스레드를 재우며 되풀이한다.
 * 받은 메시지는 기다리는 요청을 깨우기만 하므로 수신 스레드에서 바로 처리한다. 기본 실행기는 메시지마다 스레드를 만든다.
 */
@Component
class LockReleaseListener(connectionFactory: RedisConnectionFactory, lock: RedisDistributedLock) :
    ApplicationListener<ApplicationReadyEvent>, DisposableBean {

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

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        try {
            container.start()
        } catch (e: RuntimeException) {
            log.warn(e) { "잠금 해제 알림을 구독하지 못했다. 락은 재시도 간격으로만 기다리고 구독은 5초마다 다시 시도한다" }
        }
    }

    override fun destroy() {
        container.destroy()
        recovery.shutdownNow()
    }

    private companion object {
        private val log by logger()
    }
}
