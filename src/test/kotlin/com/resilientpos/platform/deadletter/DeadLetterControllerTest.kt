package com.resilientpos.platform.deadletter

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.platform.AdminSecurityFilterChain
import com.resilientpos.platform.DependencyUnavailableException
import com.resilientpos.terminal.TerminalManagement
import java.time.Instant
import kotlin.test.Test
import org.apache.kafka.common.errors.TimeoutException
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * DLT 목록 API 웹 슬라이스. reader는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·인증·오류 변환·문서 계약만 검사한다.
 * 운영자 인증은 `jwt()`로 넣는다. 토큰 검증 자체(issuer·audience·서명·만료)는 `AdminSecurityIntegrationTest`가 실제 decoder로 본다.
 */
@WebMvcTest(DeadLetterController::class)
@AutoConfigureRestDocs
class DeadLetterControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var reader: DeadLetterReader

    /** 슬라이스가 싣는 단말 인증 인터셉터의 의존성이다. 이 API는 단말을 쓰지 않는다. */
    @MockitoBean
    private lateinit var terminals: TerminalManagement

    private val operator = jwt().authorities(SimpleGrantedAuthority(AdminSecurityFilterChain.OPERATOR_AUTHORITY))

    private fun list(request: MockHttpServletRequestBuilder = listRequest()): ResultActions = mockMvc.perform(request)

    private fun listRequest(): MockHttpServletRequestBuilder =
        get("/admin/v1/dead-letters").queryParam("topic", TOPIC).queryParam("partition", "0").queryParam("fromOffset", "12").queryParam("limit", "2")

    @Test
    fun `목록은 200과 DLT 레코드를 원 위치 예외 schemaVersion과 함께 돌려주고 없는 헤더는 null이다`() {
        given(reader.read(TOPIC, 0, 12, 2)).willReturn(
            listOf(
                DeadLetterResponse(
                    partition = 0, offset = 12, timestamp = Instant.parse("2026-10-21T01:00:00Z"), key = ORDER_ID,
                    value = """{"schemaVersion":1,"orderId":"$ORDER_ID"}""", originalTopic = "order.paid", originalPartition = 0, originalOffset = 42,
                    exception = "com.resilientpos.foundation.DomainException", exceptionMessage = "Listener failed; 이미 처리된 주문입니다",
                    schemaVersion = 1,
                ),
                DeadLetterResponse(
                    partition = 0, offset = 13, timestamp = Instant.parse("2026-10-21T01:00:05Z"), key = null,
                    value = "not-json", originalTopic = null, originalPartition = null, originalOffset = null,
                    exception = null, exceptionMessage = null, schemaVersion = null,
                ),
            ),
        )

        list(listRequest().with(operator))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].partition").value(0))
            .andExpect(jsonPath("$.data[0].offset").value(12))
            .andExpect(jsonPath("$.data[0].timestamp").value("2026-10-21T01:00:00Z"))
            .andExpect(jsonPath("$.data[0].key").value(ORDER_ID))
            .andExpect(jsonPath("$.data[0].value").value("""{"schemaVersion":1,"orderId":"$ORDER_ID"}"""))
            .andExpect(jsonPath("$.data[0].originalTopic").value("order.paid"))
            .andExpect(jsonPath("$.data[0].originalPartition").value(0))
            .andExpect(jsonPath("$.data[0].originalOffset").value(42))
            .andExpect(jsonPath("$.data[0].exception").value("com.resilientpos.foundation.DomainException"))
            .andExpect(jsonPath("$.data[0].exceptionMessage").value("Listener failed; 이미 처리된 주문입니다"))
            .andExpect(jsonPath("$.data[0].schemaVersion").value(1))
            .andExpect(jsonPath("$.data[1].key").value(null))
            .andExpect(jsonPath("$.data[1].originalTopic").value(null))
            .andExpect(jsonPath("$.data[1].originalPartition").value(null))
            .andExpect(jsonPath("$.data[1].originalOffset").value(null))
            .andExpect(jsonPath("$.data[1].exception").value(null))
            .andExpect(jsonPath("$.data[1].exceptionMessage").value(null))
            .andExpect(jsonPath("$.data[1].schemaVersion").value(null))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "dead-letters-list",
                    queryParameters(
                        parameterWithName("topic").description("읽을 DLT 토픽. 설정된 토픽 이름 + `.DLT`만 받는다(예: `order.paid.DLT`). 원 토픽·내부 토픽·모르는 이름은 400 INVALID_DEAD_LETTER_POSITION"),
                        parameterWithName("partition").description("partition 번호. 그 토픽에 없으면 400 INVALID_DEAD_LETTER_POSITION"),
                        parameterWithName("fromOffset").optional().description("이 offset부터 읽는다. 기본 0. 음수면 400 INVALID_DEAD_LETTER_POSITION. log 시작보다 작으면 log 시작부터 읽는다. 다음 쪽은 마지막 항목의 `offset + 1`로 부른다"),
                        parameterWithName("limit").optional().description("최대 건수. 1~50, 기본 50. 범위 밖이면 400 INVALID_DEAD_LETTER_POSITION"),
                    ),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data[]").type(JsonFieldType.ARRAY).description("fromOffset부터 limit건 또는 partition 끝까지의 DLT 레코드(offset 오름차순). fromOffset이 끝 이상이면 빈 배열. 읽어도 어떤 consumer group의 offset도 바뀌지 않는다"),
                        fieldWithPath("data[].partition").type(JsonFieldType.NUMBER).description("DLT의 partition"),
                        fieldWithPath("data[].offset").type(JsonFieldType.NUMBER).description("DLT의 offset"),
                        fieldWithPath("data[].timestamp").type(JsonFieldType.STRING).description("DLT 레코드의 timestamp(ISO-8601 UTC)"),
                        fieldWithPath("data[].key").type(JsonFieldType.STRING).optional().description("레코드 key(이벤트의 주문 ID 등). 없으면 null"),
                        fieldWithPath("data[].value").type(JsonFieldType.STRING).optional().description("값 바이트를 UTF-8로 읽은 문자열. 매장·단말·주문 ID와 금액이 들어 있다. tombstone이면 null"),
                        fieldWithPath("data[].originalTopic").type(JsonFieldType.STRING).optional().description("원 토픽(헤더 `kafka_dlt-original-topic`). 헤더가 없으면 null"),
                        fieldWithPath("data[].originalPartition").type(JsonFieldType.NUMBER).optional().description("원 partition(헤더 `kafka_dlt-original-partition`). 헤더가 없으면 null"),
                        fieldWithPath("data[].originalOffset").type(JsonFieldType.NUMBER).optional().description("원 offset(헤더 `kafka_dlt-original-offset`). 재투입한 메시지가 다시 DLT로 오면 재투입 기록의 넣은 offset과 같다. 헤더가 없으면 null"),
                        fieldWithPath("data[].exception").type(JsonFieldType.STRING).optional().description("원인 예외 클래스(헤더 `kafka_dlt-exception-cause-fqcn`, 없으면 `kafka_dlt-exception-fqcn`). 둘 다 없으면 null. 스택 트레이스는 싣지 않는다"),
                        fieldWithPath("data[].exceptionMessage").type(JsonFieldType.STRING).optional().description("예외 메시지(헤더 `kafka_dlt-exception-message`). 없으면 null"),
                        fieldWithPath("data[].schemaVersion").type(JsonFieldType.NUMBER).optional().description("값 JSON의 schemaVersion 정수. 필드가 없거나 값이 JSON이 아니면 null"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )
    }

    @Test
    fun `fromOffset과 limit을 생략하면 0과 50으로 읽는다`() {
        given(reader.read(TOPIC, 0, 0, 50)).willReturn(emptyList())

        list(get("/admin/v1/dead-letters").queryParam("topic", TOPIC).queryParam("partition", "0").with(operator))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))

        verify(reader).read(TOPIC, 0, 0, 50)
    }

    @Test
    fun `읽을 수 없는 위치면 400 INVALID_DEAD_LETTER_POSITION이다`() {
        given(reader.read(TOPIC, 0, 12, 2)).willThrow(DomainException(INVALID_POSITION, "설정된 DLT 토픽이 아닙니다.", DomainErrorKind.INVALID))

        list(listRequest().with(operator))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value(INVALID_POSITION))
            .andDo(document("dead-letters-list-invalid-position", responseFields(failFields("INVALID_DEAD_LETTER_POSITION. topic이 설정된 DLT 토픽이 아니거나(원 토픽·`__consumer_offsets` 포함), partition이 없거나, fromOffset이 음수이거나, limit이 1~50 밖이다"))))
    }

    @Test
    fun `topic이나 partition이 없으면 400 BAD_REQUEST이고 reader를 부르지 않는다`() {
        list(get("/admin/v1/dead-letters").queryParam("partition", "0").with(operator))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))
        list(get("/admin/v1/dead-letters").queryParam("topic", TOPIC).with(operator))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("BAD_REQUEST"))

        verifyNoInteractions(reader)
    }

    @Test
    fun `브로커에 닿지 못하면 503 SERVICE_UNAVAILABLE이다`() {
        given(reader.read(TOPIC, 0, 12, 2)).willThrow(DependencyUnavailableException("kafka broker", TimeoutException("timeout")))

        list(listRequest().with(operator))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error.errorCode").value("SERVICE_UNAVAILABLE"))
            .andDo(document("dead-letters-list-unavailable", responseFields(failFields("SERVICE_UNAVAILABLE. Kafka 브로커에 닿지 못했다(메타데이터·끝 offset 조회·poll이 5초 안에 끝나지 않음). 브로커가 돌아온 뒤 다시 부른다"))))
    }

    @Test
    fun `운영자 토큰이 없으면 401 UNAUTHENTICATED와 WWW-Authenticate Bearer이고 reader를 부르지 않는다`() {
        list()
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.error.errorCode").value("UNAUTHENTICATED"))
            .andDo(document("dead-letters-unauthenticated", responseFields(failFields("UNAUTHENTICATED. `Authorization: Bearer` 운영자 토큰이 없거나 서명·만료·issuer·audience가 틀렸다. 원인을 나누지 않는다"))))

        verifyNoInteractions(reader)
    }

    @Test
    fun `운영자 권한이 없는 토큰은 403 ACCESS_DENIED이고 reader를 부르지 않는다`() {
        list(listRequest().with(jwt()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.errorCode").value("ACCESS_DENIED"))
            .andDo(document("dead-letters-access-denied", responseFields(failFields("ACCESS_DENIED. 토큰은 맞지만 `resilientpos-operator` 권한이 없다"))))

        verifyNoInteractions(reader)
    }

    companion object {
        private const val TOPIC = "order.paid.DLT"
        private const val ORDER_ID = "018f6b1a-0000-7000-8000-0000000000e1"
        private const val INVALID_POSITION = "INVALID_DEAD_LETTER_POSITION"

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
