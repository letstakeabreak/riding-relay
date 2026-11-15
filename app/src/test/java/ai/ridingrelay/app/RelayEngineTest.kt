package ai.ridingrelay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEngineTest {
    private val english = Stop("영어학원", 0.0, 0.0, "")
    private val math = Stop("수학학원", 0.0, 0.0, "")
    private val home = Stop("집", 0.0, 0.0, "")
    private fun engine() = RelayEngine(
        listOf(Rider("지우", RearSeat.LEFT, english), Rider("서준", RearSeat.RIGHT, math)), home
    ).apply {
        start()
        onOccupancy(RearSeat.LEFT, true, 0)
        onOccupancy(RearSeat.RIGHT, true, 0)
    }

    @Test fun `P단에서 문이 열리고 자리가 비면 하차 확정`() {
        val e = engine()
        e.onGear(false); e.onGear(true)
        assertNull(e.onDoor(RearSeat.LEFT, true, 10))
        val drop = e.onOccupancy(RearSeat.LEFT, false, 20)
        assertEquals(Drop("지우", "영어학원", 20), drop)
        assertEquals("수학학원", e.nextStop().name)
        assertEquals(listOf("서준"), e.onboard().map { it.name })
    }

    @Test fun `주행 중에는 자리가 비어도 하차로 치지 않는다`() {
        val e = engine()
        e.onGear(false)
        assertNull(e.onDoor(RearSeat.LEFT, true, 10))
        assertNull(e.onOccupancy(RearSeat.LEFT, false, 20))
        assertEquals("영어학원", e.nextStop().name)
    }

    @Test fun `문이 열리지 않았으면 자리가 비어도 하차가 아니다`() {
        val e = engine()
        e.onGear(false); e.onGear(true)
        assertNull(e.onOccupancy(RearSeat.LEFT, false, 20))
    }

    @Test fun `이전 정차에서 열린 문은 다음 정차에 이어지지 않는다`() {
        val e = engine()
        e.onGear(true); e.onDoor(RearSeat.LEFT, true, 1); e.onDoor(RearSeat.LEFT, false, 2)
        e.onGear(false); e.onGear(true)
        assertNull(e.onOccupancy(RearSeat.LEFT, false, 20))
    }

    @Test fun `같은 아이는 한 번만 확정되고 모두 내리면 집으로`() {
        val e = engine()
        e.onGear(true)
        e.onDoor(RearSeat.LEFT, true, 1); e.onOccupancy(RearSeat.LEFT, false, 2)
        assertNull(e.onOccupancy(RearSeat.LEFT, false, 3))
        e.onGear(false); e.onGear(true)
        e.onOccupancy(RearSeat.RIGHT, false, 4)
        assertEquals(1, e.drops.size)
        e.onDoor(RearSeat.RIGHT, true, 5)
        assertEquals(2, e.drops.size)
        assertTrue(e.allDropped())
        assertEquals("집", e.nextStop().name)
    }

    @Test fun `탑승이 확인되지 않은 자리는 하차로 치지 않는다`() {
        val e = RelayEngine(listOf(Rider("지우", RearSeat.LEFT, english)), home)
        e.start()
        e.onGear(true)
        e.onOccupancy(RearSeat.LEFT, false, 1)
        assertNull(e.onDoor(RearSeat.LEFT, true, 2))
    }

    @Test fun `출발 전에 열린 문은 하차 판정에 쓰지 않는다`() {
        val e = RelayEngine(listOf(Rider("지우", RearSeat.LEFT, english)), home)
        e.onOccupancy(RearSeat.LEFT, true, 0)
        e.onDoor(RearSeat.LEFT, true, 1)
        assertNull(e.onOccupancy(RearSeat.LEFT, false, 2))
    }

    @Test fun `내릴 곳에서 문을 열었는데 그대로 탄 채 출발하면 알린다`() {
        val e = engine()
        e.onGear(false); e.onGear(true)
        e.onDoor(RearSeat.LEFT, true, 1); e.onDoor(RearSeat.LEFT, false, 2)
        e.onGear(false)
        assertEquals(listOf("지우"), e.stillOnboard.map { it.name })
        e.onGear(true)
        assertTrue(e.stillOnboard.isEmpty())
    }

    @Test fun `문을 열지 않은 정차에서는 알리지 않는다`() {
        val e = engine()
        e.onGear(false); e.onGear(true); e.onGear(false)
        assertTrue(e.stillOnboard.isEmpty())
    }

    @Test fun `다른 아이가 내리는 곳에서 문만 열었던 아이는 알리지 않는다`() {
        val e = engine()
        e.onGear(false); e.onGear(true)
        e.onDoor(RearSeat.LEFT, true, 1); e.onOccupancy(RearSeat.LEFT, false, 2)
        e.onDoor(RearSeat.RIGHT, true, 3); e.onDoor(RearSeat.RIGHT, false, 4)
        e.onGear(false)
        assertTrue(e.stillOnboard.isEmpty())
    }

    @Test fun `달리는 중에 열린 뒷문만 알린다`() {
        val e = engine()
        e.onDoor(RearSeat.RIGHT, true, 1)
        assertTrue(e.openDoorsWhileMoving().isEmpty())
        e.onGear(false)
        assertEquals(listOf(RearSeat.RIGHT), e.openDoorsWhileMoving())
        e.onDoor(RearSeat.RIGHT, false, 2)
        assertTrue(e.openDoorsWhileMoving().isEmpty())
    }

    @Test fun `정차 중에 문을 닫았는데 그대로 앉아 있으면 출발 전에 먼저 묻는다`() {
        val e = engine()
        e.onGear(false); e.onGear(true)
        e.onDoor(RearSeat.LEFT, true, 1)
        assertTrue(e.askingAbout().isEmpty())
        e.onDoor(RearSeat.LEFT, false, 2)
        assertEquals(listOf("지우"), e.askingAbout().map { it.name })
    }

    @Test fun `운전자가 직접 확인하면 내림으로 기록하고 경고하지 않는다`() {
        val e = engine()
        val jiwoo = Rider("지우", RearSeat.LEFT, english)
        assertNull(e.confirmDrop(jiwoo, 0))
        e.onGear(false)
        assertNull(e.confirmDrop(jiwoo, 1))
        e.onGear(true)
        e.onDoor(RearSeat.LEFT, true, 2); e.onDoor(RearSeat.LEFT, false, 3)
        assertEquals(Drop("지우", "영어학원", 4), e.confirmDrop(jiwoo, 4))
        assertNull(e.confirmDrop(jiwoo, 5))
        assertTrue(e.askingAbout().isEmpty())
        e.onGear(false)
        assertTrue(e.stillOnboard.isEmpty())
        assertEquals("수학학원", e.nextStop().name)
    }

    @Test fun `여기서 내리지 않는 아이는 직접 확인할 수 없다`() {
        val e = engine()
        e.onGear(false); e.onGear(true)
        assertNull(e.confirmDrop(Rider("서준", RearSeat.RIGHT, math), 1))
    }

    @Test fun `남은 경로는 내린 아이의 학원을 빼고 집으로 끝난다`() {
        val e = engine()
        assertEquals(listOf("영어학원", "수학학원", "집"), e.route())
        e.onDoor(RearSeat.LEFT, true, 1); e.onOccupancy(RearSeat.LEFT, false, 2)
        assertEquals(listOf("수학학원", "집"), e.route())
    }

    @Test fun `하차 확인 단계는 이번 정차에서 열린 문과 지금 열린 문을 따로 알려 준다`() {
        val e = engine()
        e.onGear(false); e.onGear(true)
        assertTrue(!e.doorOpenedHere(RearSeat.LEFT) && !e.isDoorOpen(RearSeat.LEFT))
        e.onDoor(RearSeat.LEFT, true, 10)
        assertTrue(e.doorOpenedHere(RearSeat.LEFT) && e.isDoorOpen(RearSeat.LEFT))
        e.onDoor(RearSeat.LEFT, false, 20)
        assertTrue(e.doorOpenedHere(RearSeat.LEFT) && !e.isDoorOpen(RearSeat.LEFT))
        e.onGear(false)
        assertTrue(!e.doorOpenedHere(RearSeat.LEFT))
    }
}
