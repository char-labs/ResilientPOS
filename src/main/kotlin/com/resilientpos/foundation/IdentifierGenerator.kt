package com.resilientpos.foundation

import java.util.UUID

interface IdentifierGenerator {
    fun next(): UUID
}
