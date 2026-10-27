package com.resilientpos

import org.hibernate.resource.jdbc.spi.StatementInspector

/**
 * Hibernate가 준비한 SQL 문장 수를 호출한 스레드별로 센다. `hibernate.session_factory.statement_inspector`로 테스트 컨텍스트에 등록한다
 * (`config/application.properties`). Hibernate 통계의 `prepareStatementCount`는 `SessionFactory` 전체의 누적값이라
 * 다른 스레드(Kafka consumer의 결제 시도 생성 등)의 문장이 측정 구간에 섞인다. 이 카운터는 테스트 스레드가 직접 부른 문장만 센다.
 */
class ThreadStatementCounter : StatementInspector {

    override fun inspect(sql: String): String {
        count.set(count.get() + 1)
        return sql
    }

    companion object {
        private val count: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

        /** 지금 스레드가 지금까지 준비한 문장 수. 전후 차이로 쓴다. */
        fun current(): Int = count.get()
    }
}
