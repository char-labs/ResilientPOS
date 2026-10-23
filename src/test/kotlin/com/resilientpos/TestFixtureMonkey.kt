package com.resilientpos

import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin

/**
 * 테스트 전체가 쓰는 Fixture Monkey 인스턴스. 모듈별 Fixture(`OrderFixtures`, `PaymentFixtures` 등)가 이것으로 커맨드를 만든다.
 * 만들 때 플러그인 설정을 읽고 내부 캐시를 쌓으므로 Fixture마다 따로 만들지 않는다.
 */
object TestFixtureMonkey {
    val instance: FixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()
}
