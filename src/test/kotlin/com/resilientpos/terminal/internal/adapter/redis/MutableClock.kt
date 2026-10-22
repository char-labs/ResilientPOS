package com.resilientpos.terminal.internal.adapter.redis

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** limiter의 1초 창과 로그 간격을 시험하려고 시각을 손으로 움직이는 시계. */
class MutableClock(private var now: Instant = Instant.parse("2026-10-21T00:00:00Z")) : Clock() {
    fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }

    fun advanceMillis(millis: Long) {
        now = now.plusMillis(millis)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}
