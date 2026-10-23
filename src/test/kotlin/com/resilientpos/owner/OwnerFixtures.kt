package com.resilientpos.owner

import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import com.resilientpos.TestFixtureMonkey
import com.resilientpos.platform.OWNER_PASSWORD
import java.util.UUID

/** `OwnerCommand` fixture. 비밀번호는 세션 테스트와 같은 [OWNER_PASSWORD]이고, 파라미터 이름과 순서는 커맨드 생성자와 같다. */
object OwnerFixtures {

    /** 이메일은 계정마다 유일해야 하므로 호출마다 새 값을 만든다. */
    fun uniqueOwnerEmail(): String = "owner-${UUID.randomUUID()}@example.com"

    fun signUpCommand(email: String = uniqueOwnerEmail(), password: String = OWNER_PASSWORD): OwnerCommand.SignUp =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<OwnerCommand.SignUp>()
            .setExp(OwnerCommand.SignUp::email, email)
            .setExp(OwnerCommand.SignUp::password, password)
            .sample()

    fun logInCommand(email: String, password: String = OWNER_PASSWORD): OwnerCommand.LogIn =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<OwnerCommand.LogIn>()
            .setExp(OwnerCommand.LogIn::email, email)
            .setExp(OwnerCommand.LogIn::password, password)
            .sample()
}
