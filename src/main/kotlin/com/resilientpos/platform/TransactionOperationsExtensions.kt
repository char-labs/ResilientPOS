package com.resilientpos.platform

import org.springframework.transaction.support.TransactionOperations

/**
 * 블록을 트랜잭션 안에서 돌리고 그 결과를 돌려준다. 전파는 주입받은 `TransactionOperations`를 따른다(기본 `REQUIRED`).
 *
 * `execute`는 콜백이 null을 돌려줄 수 있어 결과가 nullable이다. 이 저장소의 블록은 null을 돌려주지 않으므로
 * 호출부마다 `requireNotNull`로 감싸지 않게 여기서 한 번 푼다.
 */
fun <T : Any> TransactionOperations.inTransaction(action: () -> T): T = requireNotNull(execute { action() })
