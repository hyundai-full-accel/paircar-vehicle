package com.paircar

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.paircar.core.ui.qr.QrCodes
import com.paircar.data.mock.MockCarpoolData
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.domain.model.InviteLinks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CrewInviteTest {

    private val fixedNow = LocalDateTime.of(2026, 11, 2, 18, 0)

    @Test
    fun `invite url carries a readable code and expires in 24 hours`() = runTest {
        val repository = MockCarpoolRepository(latencyMillis = 0, inviteBaseUrl = "https://paircar.example/join/", now = { fixedNow })

        val invite = repository.createInvite()

        assertEquals(InviteLinks.CODE_LENGTH, invite.code.length)
        assertTrue(invite.code.all { it in InviteLinks.CODE_ALPHABET })
        assertEquals("https://paircar.example/join?code=${invite.code}", invite.url)
        assertEquals(fixedNow.plusHours(24), invite.expiresAt)
    }

    @Test
    fun `each invite gets a new code`() = runTest {
        val repository = MockCarpoolRepository(latencyMillis = 0)
        val codes = (1..5).map { repository.createInvite().code }.toSet()
        assertNotEquals(1, codes.size)
    }

    @Test
    fun `qr code decodes back to the invite url`() {
        val url = InviteLinks.build(MockCarpoolData.INVITE_BASE_URL_PLACEHOLDER, "K7M2QX")
        val matrix = QrCodes.encode(url)

        // 화면처럼 칸을 키우고 여백을 둔 이미지를 만들어 다시 읽는다
        val scale = 6
        val margin = 4 * scale
        val side = matrix.width * scale + margin * 2
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (!matrix[x, y]) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[(margin + y * scale + dy) * side + margin + x * scale + dx] = 0xFF000000.toInt()
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))

        assertEquals(url, QRCodeReader().decode(bitmap).text)
    }
}
