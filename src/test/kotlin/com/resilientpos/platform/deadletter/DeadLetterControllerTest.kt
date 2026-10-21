package com.resilientpos.platform.deadletter

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.platform.AdminSecurityFilterChain
import com.resilientpos.platform.DependencyUnavailableException
import com.resilientpos.terminal.TerminalManagement
import java.time.Instant
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.common.errors.TimeoutException
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * DLT 목록·재투입 API 웹 슬라이스. reader와 redriver는 [MockitoBean]으로 대체하고 HTTP 매핑·직렬화·인증·오류 변환·문서 계약만 검사한다.
 * 운영자 인증은 `jwt()`로 넣는다. 토큰 검증 자체(issuer·audience·서명·만료)는 `AdminSecurityIntegrationTest`가 실제 decoder로 본다.
 */
@WebMvcTest(DeadLetterController::class)
@AutoConfigureRestDocs
@ExtendWith(OutputCaptureExtension::class)
class DeadLetterControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockitoBean
    private lateinit var reader: DeadLetterReader

    @MockitoBean
    private lateinit var redriver: DeadLetterRedriver

    /** 슬라이스가 싣는 단말 인증 인터셉터의 의존성이다. 이 API는 단말을 쓰지 않는다. */
    @MockitoBean
    private lateinit var terminals: TerminalManagement

    private val operator = jwt().jwt { it.subject(OPERATOR) }.authorities(SimpleGrantedAuthority(AdminSecurityFilterChain.OPERATOR_AUTHORITY))

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
                    schemaVersion = 1, redrive = DeadLetterRedriveSummary(partition = 0, offset = 87, operator = OPERATOR, redrivenAt = REDRIVEN_AT),
                ),
                DeadLetterResponse(
                    partition = 0, offset = 13, timestamp = Instant.parse("2026-10-21T01:00:05Z"), key = null,
                    value = "not-json", originalTopic = null, originalPartition = null, originalOffset = null,
                    exception = null, exceptionMessage = null, schemaVersion = null, redrive = null,
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
            .andExpect(jsonPath("$.data[0].redrive.partition").value(0))
            .andExpect(jsonPath("$.data[0].redrive.offset").value(87))
            .andExpect(jsonPath("$.data[0].redrive.operator").value(OPERATOR))
            .andExpect(jsonPath("$.data[0].redrive.redrivenAt").value("2026-10-21T02:00:00.123456Z"))
            .andExpect(jsonPath("$.data[1].key").value(null))
            .andExpect(jsonPath("$.data[1].originalTopic").value(null))
            .andExpect(jsonPath("$.data[1].originalPartition").value(null))
            .andExpect(jsonPath("$.data[1].originalOffset").value(null))
            .andExpect(jsonPath("$.data[1].exception").value(null))
            .andExpect(jsonPath("$.data[1].exceptionMessage").value(null))
            .andExpect(jsonPath("$.data[1].schemaVersion").value(null))
            .andExpect(jsonPath("$.data[1].redrive").value(null))
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
                        fieldWithPath("data[].redrive").type(JsonFieldType.OBJECT).optional().description("이 레코드의 재투입 기록. 재투입하지 않았으면 null"),
                        fieldWithPath("data[].redrive.partition").type(JsonFieldType.NUMBER).description("원 토픽에 넣은 partition"),
                        fieldWithPath("data[].redrive.offset").type(JsonFieldType.NUMBER).description("원 토픽에 넣은 offset. 넣은 메시지가 다시 DLT로 오면 새 DLT 레코드의 originalOffset과 같다"),
                        fieldWithPath("data[].redrive.operator").type(JsonFieldType.STRING).description("재투입한 운영자(토큰의 sub)"),
                        fieldWithPath("data[].redrive.redrivenAt").type(JsonFieldType.STRING).description("재투입 시각(ISO-8601 UTC)"),
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

    @Test
    fun `목록을 읽으면 운영자와 위치 건수를 감사 INFO 한 줄로 남긴다`(output: CapturedOutput) {
        given(reader.read(TOPIC, 0, 12, 2)).willReturn(listOf(bareItem(12), bareItem(13)))

        list(listRequest().with(operator)).andExpect(status().isOk)

        val audit = output.all.lines().single { "action=DEAD_LETTERS_LISTED" in it }
        assertTrue(audit.endsWith("audit action=DEAD_LETTERS_LISTED operator=$OPERATOR topic=$TOPIC partition=0 fromOffset=12 count=2"), audit)
        assertTrue(" INFO " in audit, audit)
    }

    /** resource server는 `sub`를 검사하지 않는다. 틀린 `sub`가 감사 줄에 들어가거나 255자 컬럼에 막혀 보낸 뒤 커밋에서 실패하지 않게 협력자를 부르기 전에 막는다. */
    @Test
    fun `토큰의 sub가 없거나 비었거나 256자이거나 제어 문자를 담으면 목록과 재투입 모두 500이고 reader와 redriver를 부르지 않는다`() {
        val subjects = mapOf<String, Consumer<Jwt.Builder>>(
            "sub 없음" to Consumer { token -> token.claims { it.remove(JwtClaimNames.SUB) } },
            "빈 sub" to Consumer { it.subject("") },
            "256자" to Consumer { it.subject("s".repeat(256)) },
            "개행" to Consumer { it.subject("$OPERATOR\naudit action=DEAD_LETTER_REDRIVEN operator=forged") },
        )

        subjects.forEach { (case, claims) ->
            val principal = jwt().jwt(claims).authorities(SimpleGrantedAuthority(AdminSecurityFilterChain.OPERATOR_AUTHORITY))
            assertEquals(500, list(listRequest().with(principal)).andReturn().response.status, "목록 $case")
            assertEquals(500, mockMvc.perform(redriveRequest(principal = principal)).andReturn().response.status, "재투입 $case")
        }

        verifyNoInteractions(reader, redriver)
    }

    @Test
    fun `토큰의 sub가 255자면 운영자로 받는다`() {
        val subject = "s".repeat(255)
        given(reader.read(TOPIC, 0, 12, 2)).willReturn(emptyList())

        list(listRequest().with(jwt().jwt { it.subject(subject) }.authorities(SimpleGrantedAuthority(AdminSecurityFilterChain.OPERATOR_AUTHORITY))))
            .andExpect(status().isOk)
    }

    private fun redriveRequest(body: String = """{"reason":"$REASON"}""", principal: RequestPostProcessor = operator): MockHttpServletRequestBuilder =
        post("/admin/v1/dead-letters/{topic}/{partition}/{offset}/redrive", TOPIC, 0, 12)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .with(principal)

    @Test
    fun `재투입은 200과 넣은 자리 운영자 시각을 돌려주고 토큰의 sub를 운영자로 넘긴다`() {
        given(redriver.redrive(TOPIC, 0, 12, REASON, OPERATOR))
            .willReturn(DeadLetterRedriveResponse(TOPIC, 0, 12, "order.paid", 0, 87, OPERATOR, REDRIVEN_AT))

        mockMvc.perform(redriveRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.dltTopic").value(TOPIC))
            .andExpect(jsonPath("$.data.dltPartition").value(0))
            .andExpect(jsonPath("$.data.dltOffset").value(12))
            .andExpect(jsonPath("$.data.topic").value("order.paid"))
            .andExpect(jsonPath("$.data.partition").value(0))
            .andExpect(jsonPath("$.data.offset").value(87))
            .andExpect(jsonPath("$.data.operator").value(OPERATOR))
            .andExpect(jsonPath("$.data.redrivenAt").value("2026-10-21T02:00:00.123456Z"))
            .andExpect(jsonPath("$.error").value(null))
            .andDo(
                document(
                    "dead-letters-redrive",
                    pathParameters(
                        parameterWithName("topic").description("재투입할 DLT 토픽. 설정된 토픽 이름 + `.DLT`만 받는다(예: `order.paid.DLT`). 아니면 400 INVALID_DEAD_LETTER_POSITION"),
                        parameterWithName("partition").description("DLT의 partition. 그 토픽에 없으면 400 INVALID_DEAD_LETTER_POSITION"),
                        parameterWithName("offset").description("DLT의 offset. 음수면 400 INVALID_DEAD_LETTER_POSITION, 그 자리에 레코드가 없으면 404 DEAD_LETTER_NOT_FOUND"),
                    ),
                    requestFields(
                        fieldWithPath("reason").type(JsonFieldType.STRING).description("재투입 사유. 앞뒤 공백을 지운 뒤 1~500자, NUL 문자 금지. DB 기록에만 남고 로그에는 남지 않는다. 같은 좌표를 다시 요청하면 처음 사유가 유지된다"),
                    ),
                    responseFields(
                        fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
                        fieldWithPath("data.dltTopic").type(JsonFieldType.STRING).description("재투입한 DLT 토픽"),
                        fieldWithPath("data.dltPartition").type(JsonFieldType.NUMBER).description("재투입한 DLT partition"),
                        fieldWithPath("data.dltOffset").type(JsonFieldType.NUMBER).description("재투입한 DLT offset"),
                        fieldWithPath("data.topic").type(JsonFieldType.STRING).description("넣은 원 토픽(DLT 이름에서 `.DLT`를 뺀 것). 같은 key와 같은 값 바이트를 헤더 없이 넣는다"),
                        fieldWithPath("data.partition").type(JsonFieldType.NUMBER).description("원 토픽에 넣은 partition(producer가 key로 고른다)"),
                        fieldWithPath("data.offset").type(JsonFieldType.NUMBER).description("원 토픽에 넣은 offset. 넣은 메시지가 다시 DLT로 오면 새 DLT 레코드의 originalOffset과 같다"),
                        fieldWithPath("data.operator").type(JsonFieldType.STRING).description("재투입한 운영자(토큰의 sub)"),
                        fieldWithPath("data.redrivenAt").type(JsonFieldType.STRING).description("재투입 시각(ISO-8601 UTC). 같은 좌표를 다시 요청하면 다시 보내지 않고 처음 기록을 그대로 돌려준다"),
                        fieldWithPath("error").type(JsonFieldType.NULL).description("성공 응답에서는 null"),
                    ),
                ),
            )
    }

    @Test
    fun `사유가 규칙에 어긋나면 400 INVALID_REDRIVE_REASON이고 본문에 사유가 없으면 빈 사유로 넘긴다`() {
        val invalid = DomainException(DeadLetterRedriver.INVALID_REDRIVE_REASON, "사유는 앞뒤 공백을 지운 뒤 1~500자여야 합니다.", DomainErrorKind.INVALID)
        given(redriver.redrive(TOPIC, 0, 12, "   ", OPERATOR)).willThrow(invalid)
        given(redriver.redrive(TOPIC, 0, 12, "", OPERATOR)).willThrow(invalid)

        mockMvc.perform(redriveRequest("""{"reason":"   "}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_REDRIVE_REASON"))
            .andDo(document("dead-letters-redrive-invalid-reason", responseFields(failFields("INVALID_REDRIVE_REASON. 사유가 없거나, 앞뒤 공백을 지운 뒤 비었거나 500자를 넘거나, NUL 문자가 있다. 보내지도 기록하지도 않는다"))))
        mockMvc.perform(redriveRequest("{}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.errorCode").value("INVALID_REDRIVE_REASON"))
    }

    @Test
    fun `그 offset에 레코드가 없으면 404 DEAD_LETTER_NOT_FOUND다`() {
        given(redriver.redrive(TOPIC, 0, 12, REASON, OPERATOR))
            .willThrow(DomainException(DeadLetterRedriver.DEAD_LETTER_NOT_FOUND, "그 offset에 DLT 레코드가 없습니다.", DomainErrorKind.NOT_FOUND))

        mockMvc.perform(redriveRequest())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.errorCode").value("DEAD_LETTER_NOT_FOUND"))
            .andDo(document("dead-letters-redrive-not-found", responseFields(failFields("DEAD_LETTER_NOT_FOUND. 그 DLT partition의 offset에 레코드가 없다(log 끝 이상이거나 시작보다 앞). 보내지도 기록하지도 않는다"))))
    }

    @Test
    fun `원 토픽의 이벤트 타입으로 읽지 못하는 메시지면 422 DEAD_LETTER_NOT_READABLE이다`() {
        val message = "지금 코드가 읽지 못하는 메시지입니다: UNSUPPORTED_EVENT_SCHEMA_VERSION"
        given(redriver.redrive(TOPIC, 0, 12, REASON, OPERATOR))
            .willThrow(DomainException(DeadLetterRedriver.DEAD_LETTER_NOT_READABLE, message, DomainErrorKind.UNPROCESSABLE))

        mockMvc.perform(redriveRequest())
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.error.errorCode").value("DEAD_LETTER_NOT_READABLE"))
            .andExpect(jsonPath("$.error.message").value(message))
            .andDo(
                document(
                    "dead-letters-redrive-not-readable",
                    responseFields(
                        failFields(
                            "DEAD_LETTER_NOT_READABLE. 값을 원 토픽의 이벤트 타입으로 consumer와 같은 변환으로 읽지 못했다(아는 것보다 높은 schemaVersion, 모르는 enum 값, JSON이 아님, 값 없음). " +
                                "메시지 끝에 도메인 코드(예: UNSUPPORTED_EVENT_SCHEMA_VERSION)나 Jackson 예외 클래스 이름(예: InvalidFormatException)이 붙고 값 내용은 싣지 않는다. " +
                                "보내지도 기록하지도 않는다. 요청을 받은 서버의 코드로 확인하므로, 그 버전·값을 아는 코드가 모든 서버에 배포된 뒤 다시 요청한다",
                        ),
                    ),
                ),
            )
    }

    companion object {
        private const val TOPIC = "order.paid.DLT"
        private const val ORDER_ID = "018f6b1a-0000-7000-8000-0000000000e1"
        private const val INVALID_POSITION = "INVALID_DEAD_LETTER_POSITION"
        private const val OPERATOR = "operator-subject-1"
        private const val REASON = "원인 수정 후 재투입"
        private val REDRIVEN_AT: Instant = Instant.parse("2026-10-21T02:00:00.123456Z")

        /** 판단에 쓰이지 않는 목록 항목. 헤더 없이 CLI로 넣은 레코드 모양이다. */
        private fun bareItem(offset: Long) = DeadLetterResponse(
            partition = 0, offset = offset, timestamp = Instant.parse("2026-10-21T01:00:00Z"), key = null, value = "{}",
            originalTopic = null, originalPartition = null, originalOffset = null, exception = null, exceptionMessage = null, schemaVersion = null, redrive = null,
        )

        private fun failFields(errorCodeDescription: String): List<FieldDescriptor> = listOf(
            fieldWithPath("result").type(JsonFieldType.STRING).description("SUCCESS 또는 FAIL"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 응답에서는 null"),
            fieldWithPath("error.errorCode").type(JsonFieldType.STRING).description(errorCodeDescription),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("오류 메시지"),
        )
    }
}
