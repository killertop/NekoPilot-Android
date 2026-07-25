package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.v2ray.parseVmess
import io.nekohasekai.sagernet.ktx.MAX_PROFILE_LINK_CHARS
import org.junit.Assert.assertThrows
import org.junit.Test

class V2RayFmtLimitTest {
    @Test
    fun rejectsOversizedVmessPayloadBeforeBase64OrJsonAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            parseVmess("vmess://" + "A".repeat(MAX_PROFILE_LINK_CHARS))
        }
    }
}
