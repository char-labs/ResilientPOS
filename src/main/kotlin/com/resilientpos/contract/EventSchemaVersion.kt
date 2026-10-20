package com.resilientpos.contract

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException

/** 이벤트가 아는 형식 버전보다 높은 메시지를 받았다. 새 버전을 배포한 뒤 재투입한다(docs/wiki/event-contract.md). */
const val UNSUPPORTED_EVENT_SCHEMA_VERSION = "UNSUPPORTED_EVENT_SCHEMA_VERSION"

/**
 * 외부로 나가는 이벤트의 `init`에서 부른다. Jackson이 생성자를 부르므로 Kafka consumer와 발행 기록 복원 모두에 걸린다.
 * 롤링 배포 중 옛 pod가 새 형식을 옛 모양으로 읽어, 값을 잃은 채 처리하거나 다시 보내지 않게 한다.
 * 던지면 consumer는 재시도 없이 DLT로 보내고 이 code로 센다.
 */
fun requireKnownSchemaVersion(event: String, version: Int, known: Int) {
    if (version > known) {
        throw DomainException(
            UNSUPPORTED_EVENT_SCHEMA_VERSION,
            "$event 형식 버전 ${version}을 처리할 수 없습니다. 이 버전은 ${known}까지 압니다",
            DomainErrorKind.UNPROCESSABLE,
        )
    }
}
