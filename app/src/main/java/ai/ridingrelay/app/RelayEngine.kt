package ai.ridingrelay.app

/** 뒷좌석 자리. 차량 SDK의 ROW_2_LEFT, ROW_2_RIGHT에 대응한다. */
enum class RearSeat { LEFT, RIGHT }

data class Stop(val name: String, val lat: Double, val lng: Double, val address: String)

/** 아이 한 명: 어느 자리에 앉아 어디서 내리는지. note는 부모가 남긴 전달 메모(누구에게 맡기는지). */
data class Rider(val name: String, val seat: RearSeat, val stop: Stop, val note: String = "")

data class Drop(val rider: String, val stop: String, val timeMs: Long)

/**
 * 하차 판정 규칙. 차량 신호만 받고 화면과 SDK는 모른다.
 *
 * 하차 확정 조건: P단이고, P단으로 바꾼 뒤 그 자리의 문이 열린 적이 있고,
 * 타고 있던 아이의 좌석 점유가 꺼졌을 때. 주행 중 점유 신호가 꺼지는 것은 무시한다.
 *
 * 지켜 주기: 내릴 곳에서 문을 열었는데 아이가 그대로 탄 채 출발하면 [stillOnboard]로 알리고,
 * 뒷문이 열린 채 달리면 [openDoorsWhileMoving]으로 알린다. 문을 열지 않은 정차(신호 대기)는 알리지 않는다.
 */
class RelayEngine(private val riders: List<Rider>, private val home: Stop) {
    private var parked = true
    private val occupied = mutableMapOf<RearSeat, Boolean>()
    private val doorOpenedSincePark = mutableSetOf<RearSeat>()
    private val boarded = mutableSetOf<RearSeat>()
    private val dropped = mutableListOf<Drop>()
    private val doorOpen = mutableSetOf<RearSeat>()
    private var arrivedStop: Stop? = null

    /** 출발 버튼을 누른 뒤인지. 출발 전의 문 열림은 탑승 과정이므로 하차 판정에 쓰지 않는다. */
    var started = false
        private set

    /** 방금 떠난 정차 지점에서 문은 열렸지만 내리지 않은 아이. 다시 P단이 되면 비운다. */
    var stillOnboard: List<Rider> = emptyList()
        private set

    fun start() { started = true }

    val drops: List<Drop> get() = dropped

    fun onGear(isParked: Boolean) {
        if (parked && !isParked) {
            stillOnboard = riders.filter { stayed(it) }
        }
        if (isParked) {
            stillOnboard = emptyList()
            if (!parked && started) arrivedStop = nextStop()
        }
        parked = isParked
        if (!isParked) doorOpenedSincePark.clear()
    }

    fun onDoor(seat: RearSeat, open: Boolean, nowMs: Long): Drop? {
        if (open) doorOpen += seat else doorOpen -= seat
        if (started && parked && open) doorOpenedSincePark += seat
        return check(seat, nowMs)
    }

    fun onOccupancy(seat: RearSeat, isOccupied: Boolean, nowMs: Long): Drop? {
        occupied[seat] = isOccupied
        if (isOccupied) boarded += seat
        return check(seat, nowMs)
    }

    private fun check(seat: RearSeat, nowMs: Long): Drop? {
        val rider = riders.firstOrNull { it.seat == seat } ?: return null
        if (dropped.any { it.rider == rider.name }) return null
        if (!parked || seat !in doorOpenedSincePark || seat !in boarded) return null
        if (occupied[seat] != false) return null
        return Drop(rider.name, rider.stop.name, nowMs).also { dropped += it }
    }

    /** 여기서 내릴 아이인데, 문이 열린 적이 있는데도 그대로 앉아 있는지. */
    private fun stayed(rider: Rider): Boolean =
        rider.stop == arrivedStop && rider.seat in doorOpenedSincePark &&
            occupied[rider.seat] == true && dropped.none { it.rider == rider.name }

    /** 정차 중에 문을 열었다 닫았는데 그대로 앉아 있는 아이. 출발하기 전에 먼저 물어보기 위한 목록. */
    fun askingAbout(): List<Rider> =
        if (parked) riders.filter { stayed(it) && it.seat !in doorOpen } else emptyList()

    /** 여기서 내릴 아이를 운전자가 직접 확인할 수 있는지. 정차 중에만 된다. */
    fun canConfirm(rider: Rider): Boolean =
        parked && started && rider.stop == arrivedStop && rider.seat in boarded &&
            dropped.none { it.rider == rider.name }

    /** 센서가 틀렸을 때(가방이 자리에 남은 경우 등) 운전자가 내림을 직접 확인한다. */
    fun confirmDrop(rider: Rider, nowMs: Long): Drop? {
        if (!canConfirm(rider)) return null
        return Drop(rider.name, rider.stop.name, nowMs).also { dropped += it }
    }

    /** 아직 타고 있는 아이 (탑승이 확인된 아이만). */
    fun onboard(): List<Rider> =
        riders.filter { it.seat in boarded && dropped.none { d -> d.rider == it.name } }

    /** 다음 목적지: 아직 내리지 않은 첫 아이의 학원, 모두 내렸으면 집. */
    fun nextStop(): Stop =
        riders.firstOrNull { dropped.none { d -> d.rider == it.name } }?.stop ?: home

    /** 하차 확인 3단계 중 첫 단계: 이번 정차에서 그 자리 문이 열린 적이 있는지. */
    fun doorOpenedHere(seat: RearSeat): Boolean = seat in doorOpenedSincePark

    /** 하차 확인 3단계 중 마지막 단계에 쓴다: 그 자리 문이 지금 열려 있는지. */
    fun isDoorOpen(seat: RearSeat): Boolean = seat in doorOpen

    /** 달리는 중에 열려 있는 뒷문. 정차 중에는 비어 있다. */
    fun openDoorsWhileMoving(): List<RearSeat> = if (parked) emptyList() else doorOpen.sorted()

    /** 앞으로 들를 곳의 이름. 내릴 아이가 남은 곳을 순서대로, 마지막은 집. */
    fun route(): List<String> =
        riders.filter { dropped.none { d -> d.rider == it.name } }.map { it.stop.name }.distinct() + home.name

    fun allDropped(): Boolean = dropped.size == riders.size
}
