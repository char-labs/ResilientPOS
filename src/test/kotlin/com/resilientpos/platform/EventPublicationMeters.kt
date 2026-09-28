package com.resilientpos.platform

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.search.Search

/** 한 리스너의 끝나지 않은 발행 기록 gauge(`status`별). */
fun MeterRegistry.incomplete(listener: String): Search = find("event.publication.incomplete").tag("listener", listener)

/** 발행 기록 개수를 마지막으로 다시 센 시각 gauge. */
fun MeterRegistry.refreshedGauge(): Gauge = get("event.publication.incomplete.refreshed").gauge()
