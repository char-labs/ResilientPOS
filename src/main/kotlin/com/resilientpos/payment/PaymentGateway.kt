package com.resilientpos.payment

import com.resilientpos.foundation.Money

/**
 * 결제 provider를 부르는 경계. provider SDK DTO·HTTP 상태·비밀값·클라이언트 라이브러리를 노출하지 않는다.
 * provider별 요청·응답 매핑과 오류 분류는 각 adapter가 소유한다.
 *
 * 원문은 `suspend` 함수로 그렸지만 블로킹으로 둔다. 이 저장소에 코루틴 의존성이 없고 Spring MVC가 블로킹이다.
 *
 * 취소(`cancel`)와 조회(`findByMerchantReference`)는 아직 없다. 각각 취소 시도와 미확인 수렴을 다루는
 * PR이 그 전이와 함께 더한다. 부르는 쪽 없이 인터페이스를 먼저 넓히지 않는다.
 */
interface PaymentGateway {

    fun authorize(command: AuthorizePayment): GatewayAuthorization
}

/**
 * provider에 보낼 승인 요청.
 *
 * `merchantReference`는 이 시도를 provider 쪽에서 가리키는 이름이고 재전송에도 같은 값을 쓴다.
 * `idempotencyKey`는 **같은 외부 요청의 재전송**에만 재사용하며 새 시도에 돌려쓰지 않는다.
 * 둘을 한 값으로 합치지 않는 이유가 그것이다.
 */
data class AuthorizePayment(
    val merchantReference: String,
    val idempotencyKey: String,
    val amount: Money,
)

/**
 * 승인 요청의 결과. 세 갈래를 타입으로 갈라 두면 "timeout은 실패가 아니다"를 호출부가 잊을 수 없다.
 * `else` 없는 `when`으로 다루므로 갈래를 더하면 매핑을 빠뜨린 곳이 컴파일되지 않는다.
 */
sealed interface GatewayAuthorization {

    /** provider가 승인했다. `providerReference`로 조회·취소한다. */
    data class Approved(val providerReference: String, val approvedAmount: Money) : GatewayAuthorization

    /** provider가 거절했다. 결과를 받았으므로 다시 묻지 않는다. */
    data class Declined(val reasonCode: String) : GatewayAuthorization

    /**
     * 결과를 확인하지 못했다. **실패로 단정하지 않는다.**
     * 같은 요청을 새 승인으로 재시도하면 이중 승인이 될 수 있으므로 조회와 reconciliation으로 수렴한다.
     */
    data class Unknown(val reason: String) : GatewayAuthorization
}
