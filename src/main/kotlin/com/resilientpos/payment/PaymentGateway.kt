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

    /**
     * 승인을 요청한다. 로컬 결함(설정 오류, 인증 실패처럼 provider에 닿기 전의 실패)은 결과로 담지 않고
     * 예외로 던진다. 배포 버그를 결제 모호성으로 둔갑시키지 않기 위해서다.
     *
     * 호출부는 [GatewayAuthorization.Approved.approvedAmount]를 요청 금액과 **반드시 대조한다.**
     * 결제 승인 금액이 checkout과 일치해야 한다는 것이 `order`의 불변식이고, 그 대조가 유일한 근거다.
     */
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
) {
    init {
        // 비어 있으면 서로 다른 시도가 같은 provider 참조를 받아 재전송과 중복 승인을 구별할 수 없게 된다.
        require(merchantReference.isNotBlank()) { "merchantReference는 비어 있을 수 없습니다" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey는 비어 있을 수 없습니다" }
    }
}

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
     *
     * `reason`은 분류값이지 provider 응답 본문이 아니다. 원문을 그대로 실으면 비밀값이 로그로 샌다.
     */
    data class Unknown(val reason: String) : GatewayAuthorization

    /**
     * 요청을 **보내지 않았다.** circuit open, bulkhead 거절, outbound rate limit이 여기에 온다.
     *
     * [Unknown]과 복구 경로가 정반대라서 갈래를 따로 둔다. provider 쪽 부수효과가 확실히 없으므로
     * 다시 보내도 안전하고, 조회할 참조도 없다. 이것을 [Unknown]으로 적으면 일어나지 않은 결제에
     * reconciliation case와 미확인 알람이 쌓인다.
     */
    data class NotAttempted(val reason: String) : GatewayAuthorization
}
