package ai.ridingrelay.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    @Test fun `16대9 화면의 앱 창은 넓고 세로가 넉넉하다`() {
        assertTrue(Layout.isWide(1706))
        assertFalse(Layout.isShort(1706, 1210))
    }

    @Test fun `가로로 긴 차량 화면의 앱 창은 가로형 배치를 쓴다`() {
        assertTrue(Layout.isShort(1706, 730))
    }

    @Test fun `좁은 창은 세로가 낮아도 가로형 배치를 쓰지 않는다`() {
        assertFalse(Layout.isWide(800))
        assertFalse(Layout.isShort(800, 730))
    }
}
