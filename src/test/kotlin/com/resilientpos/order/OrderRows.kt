package com.resilientpos.order

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/** 저장된 주문 상태. 여러 테스트가 Facade 결과가 아니라 커밋된 행을 확인할 때 쓴다. */
fun JdbcTemplate.orderStatus(orderId: UUID): String? =
    queryForObject<String>("select status from t_order where id = ?", orderId)

/** 그 주문에 저장된 checkout 행 수. 겹친 요청이 회차를 두 번 만들지 않았는지 본다. */
fun JdbcTemplate.checkoutRowCount(orderId: UUID): Long? =
    queryForObject<Long>("select count(*) from t_order_checkout where order_id = ?", orderId)
