package ai.ridingrelay.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviHelper
    private var naviReady = false
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val ttsListener = object : EventListener {
        override fun onStart() { Log.i(TAG, "tts start") }
        override fun onReady() {}
        override fun onDone() { Log.i(TAG, "tts done") }
        override fun onError(error: ErrorType) { Log.w(TAG, "tts error: $error") }
        override fun onStop() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    // 시연용 고정 일정. 실제 서비스에서는 부모가 정차 중에 등록한 요일별 일정을 쓴다.
    private val english = Stop("대치 영어학원", 37.4946, 127.0630, "서울특별시 강남구 대치동")
    private val math = Stop("은마 수학학원", 37.4990, 127.0590, "서울특별시 강남구 대치동")
    private val home = Stop("집", 37.5040, 127.0490, "서울특별시 강남구 역삼동")
    // 이름은 부모가 부르는 이름으로 등록한다.
    // 전달 메모는 부모가 남긴다. 일정을 모르는 사람이 대신 태워 줄 때 도착하면 글과 소리로 알려 준다.
    private val riders = listOf(
        Rider("지우", RearSeat.LEFT, english, "2층 데스크에 맡겨 주세요"),
        Rider("서준", RearSeat.RIGHT, math, "1층 입구에서 선생님이 기다려요")
    )
    private val engine = RelayEngine(riders, home)

    private var parked = true
    private val started get() = engine.started
    private var lastWarning: String? = null
    private var lastDrop: Drop? = null
    private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)


    private val seats = mapOf(
        RearSeat.LEFT to SeatArea(SeatValue.ROW_2_LEFT),
        RearSeat.RIGHT to SeatArea(SeatValue.ROW_2_RIGHT)
    )
    private val doors = mapOf(
        RearSeat.LEFT to DoorArea(DoorValue.ROW_2_LEFT),
        RearSeat.RIGHT to DoorArea(DoorValue.ROW_2_RIGHT)
    )
    private val seatListeners = RearSeat.values().associateWith { seat ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupied: Boolean?) {
                occupied?.let { runOnUiThread { onOccupancy(seat, it) } }
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "seat listener $seat", e) }
        }
    }
    private val doorListeners = RearSeat.values().associateWith { seat ->
        object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) {
                position?.let { runOnUiThread { onDoor(seat, it > 0) } }
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "door listener $seat", e) }
        }
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { gear?.let { runOnUiThread { onGear(it) } } }
        override fun onFailed(e: Exception) { Log.w(TAG, "gear listener", e) }
    }
    private val naviListener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() { Log.i(TAG, "navi routing completed") }
        override fun onWaypointChanged(info: WaypointChangedInfo) { Log.i(TAG, "navi waypoint: $info") }
        override fun onError(code: NaviErrorCode) { Log.w(TAG, "navi error: $code") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pleos TextToSpeech SDK, 기기 내 합성 모드(앱 등록 없이 사용)
        tts = TextToSpeech(this, Mode.ON_DEVICE)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                ttsReady = true
                tts.addEventListener(ttsListener) // SDK는 초기화가 끝난 뒤에만 리스너를 받는다
                tts.setLocale(Locale.KOREAN)
                Log.i(TAG, "tts ready")
            }
            override fun onFailure(error: ErrorType) { Log.w(TAG, "tts init failed: $error") }
        })

        vehicle = Vehicle(this).also { it.initialize() }
        vehicle.getGear().getCurrentGearSelectionState(
            { gear -> gear?.let { runOnUiThread { onGear(it) } } },
            { e -> Log.w(TAG, "gear get", e) }
        )
        vehicle.getGear().registerGearSelection(gearListener)
        for (seat in RearSeat.values()) {
            vehicle.getSeat().getCurrentSeatOccupancy(
                seats.getValue(seat),
                { occupied -> occupied?.let { runOnUiThread { onOccupancy(seat, it) } } },
                { e -> Log.w(TAG, "seat get $seat", e) }
            )
            vehicle.getSeat().registerSeatOccupancy(seats.getValue(seat), seatListeners.getValue(seat))
            vehicle.getDoor().registerDoorPosition(doors.getValue(seat), doorListeners.getValue(seat))
        }

        navi = NaviHelper(this)
        navi.addListener(naviListener)
        navi.initialize({ naviReady = true; Log.i(TAG, "navi ready"); runOnUiThread { render() } }, { e -> Log.w(TAG, "navi init", e) })

        render()
    }

    override fun onDestroy() {
        vehicle.getGear().unregisterGearSelection(gearListener)
        for (seat in RearSeat.values()) {
            vehicle.getSeat().unregisterSeatOccupancy(seats.getValue(seat), seatListeners.getValue(seat))
            vehicle.getDoor().unregisterDoorPosition(doors.getValue(seat), doorListeners.getValue(seat))
        }
        vehicle.release()
        navi.removeListener(naviListener)
        navi.release()
        if (ttsReady) tts.removeEventListener(ttsListener)
        tts.release()
        stopService(Intent(this, KeepAliveService::class.java))
        super.onDestroy()
    }

    private fun onGear(gear: VehicleGear) {
        val arrived = !parked && gear == VehicleGear.GEAR_PARK
        parked = gear == VehicleGear.GEAR_PARK
        Log.i(TAG, "gear parked=$parked")
        engine.onGear(parked)
        if (!parked) lastDrop = null
        render()
        warn()
        // 도착하면 누가 내릴 차례인지와 전달 메모를 소리로 알려 준다
        if (arrived) riders.filter { engine.canConfirm(it) }.takeIf { it.isNotEmpty() }?.let { off ->
            val names = off.joinToString(", ") { it.name }
            speak("$names${josa(names, "이", "가")} 내릴 차례예요. ${off.joinToString(" ") { it.note }}".trim())
        }
    }

    private fun onOccupancy(seat: RearSeat, sdkValue: Boolean) {
        val occupied = if (SEAT_VALUE_INVERTED) !sdkValue else sdkValue
        Log.i(TAG, "seat $seat occupied=$occupied (sdk=$sdkValue)")
        onDrop(engine.onOccupancy(seat, occupied, System.currentTimeMillis()))
    }

    private fun onDoor(seat: RearSeat, open: Boolean) {
        Log.i(TAG, "door $seat open=$open")
        onDrop(engine.onDoor(seat, open, System.currentTimeMillis()))
        warn()
    }

    /** 지금 알릴 주의. 문 열림이 먼저이고, 그다음이 내리지 않은 아이다. 없으면 null. */
    private fun warning(): Pair<String, String>? {
        val open = engine.openDoorsWhileMoving()
        if (open.isNotEmpty()) {
            if (open.size > 1) return "뒷문이 열려 있어요" to "양쪽 문을 확인해 주세요"
            val side = if (open[0] == RearSeat.LEFT) "왼쪽" else "오른쪽"
            val rider = riders.firstOrNull { it.seat == open[0] }
            return "$side 뒷문이 열려 있어요" to (rider?.let { "${it.name} 쪽 문이에요" } ?: "문을 확인해 주세요")
        }
        val still = engine.stillOnboard
        if (still.isNotEmpty()) {
            val names = still.joinToString(", ") { it.name }
            return "$names${josa(names, "이", "가")} 아직 타고 있어요" to "${still[0].stop.name}에서 내리지 않았어요"
        }
        return null
    }

    /** 주의가 새로 생겼을 때 한 번만 소리로 알린다. */
    private fun warn() {
        val message = warning()?.let { "${it.first}. ${it.second}." }
        if (message != null && message != lastWarning) speak(message)
        lastWarning = message
    }

    private fun onDrop(drop: Drop?) {
        if (drop != null) {
            lastDrop = drop
            val next = engine.nextStop()
            Log.i(TAG, "drop confirmed: $drop, next=${next.name}")
            val message =
                if (engine.allDropped()) "${drop.rider} 잘 내렸어요. 모두 잘 내렸으니 집으로 갈게요."
                else "${drop.rider} 잘 내렸어요. 다음은 ${next.name}이에요."
            speak(message)
            notifyDrop(drop, message)
            route(next)
        }
        render()
    }

    /** 내비가 앞에 있어도 보이도록 하차 확인을 시스템 알림으로 띄운다. */
    private fun notifyDrop(drop: Drop, message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("drop", "내린 기록", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.notify(
            10 + engine.drops.size,
            Notification.Builder(this, "drop")
                .setContentTitle("${drop.rider} 잘 내렸어요  ${clock.format(Date(drop.timeMs))}")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setLargeIcon(faceIcon(drop.rider))
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .build()
        )
    }

    /** 알림에서도 아이가 같은 얼굴로 보이도록 프로필을 그려 넣는다. */
    private fun faceIcon(name: String): Icon {
        val size = dp(96)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        AvatarView(looks[name]).apply {
            layout(0, 0, size, size); draw(Canvas(bitmap))
        }
        return Icon.createWithBitmap(bitmap)
    }

    private fun startRelay() {
        engine.start()
        startForegroundService(Intent(this, KeepAliveService::class.java))
        val first = engine.nextStop()
        speak("오늘 라이딩을 시작할게요. 먼저 ${first.name}${josa(first.name, "으로", "로")} 갈게요.")
        route(first)
        render()
    }

    private fun route(stop: Stop) {
        if (!naviReady) { Log.w(TAG, "navi not ready, skip route to ${stop.name}"); return }
        Log.i(TAG, "request route: ${stop.name}")
        navi.requestRoute(
            RouteInfo(
                latitude = stop.lat, longitude = stop.lng,
                displayLatitude = null, displayLongitude = null,
                poiName = stop.name, poiId = "", poiSubId = "0", address = stop.address
            )
        )
    }

    /** 화면은 상태가 바뀔 때마다 통째로 다시 그린다. 주행 중에는 움직이는 요소가 없다. */
    private fun render() = setContentView(buildScreen())

    /** 아이 프로필 그림. 색만이 아니라 머리 모양으로도 구분된다. */
    private class Look(val bg: Int, val shirt: Int, val bob: Boolean)
    private val looks = mapOf(
        "지우" to Look(0xFFE4DEFF.toInt(), 0xFF7B61FF.toInt(), true),
        "서준" to Look(0xFFFFDCE7.toInt(), 0xFFE8618C.toInt(), false)
    )

    private val match = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
    }
    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, start: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); marginStart = dp(start) }
    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(3), stroke)
    }
    private fun seatText(r: Rider) = if (r.seat == RearSeat.LEFT) "뒷좌석 왼쪽" else "뒷좌석 오른쪽"
    private fun dropOf(r: Rider) = engine.drops.firstOrNull { it.rider == r.name }

    /**
     * 넓은 창은 두 단: 왼쪽에 지금 할 일 하나, 오른쪽에 오늘 정차지와 타고 있는 아이.
     * 좁은 창(앱 두 개가 나란히 뜰 때)은 같은 내용을 한 단으로 쌓는다. 경고는 맨 위 전체 폭 띠로 보여 준다.
     */
    private fun buildScreen(): View {
        val c = colors
        val wide = resources.configuration.screenWidthDp >= 1000
        val left = col()
        content(left, wide)
        val root = col().apply { setBackgroundColor(c.background); setPadding(dp(40), dp(40), dp(40), dp(40)) }
        if (!parked) warning()?.let { root.addView(banner(it.first), lp(match, dp(104)).apply { bottomMargin = dp(24) }) }
        if (wide) {
            root.addView(row().apply {
                gravity = Gravity.TOP
                addView(left, lp(0, match, 1f, top = 16, start = 16).apply { marginEnd = dp(40) })
                addView(stopsPanel(true), lp(dp(592), match))
            }, lp(match, 0, 1f))
            return root
        }
        root.addView(left, lp(match, wrap))
        root.addView(stopsPanel(false), lp(match, wrap, top = 32))
        return ScrollView(this).apply { setBackgroundColor(c.background); addView(root) }
    }

    private fun content(v: LinearLayout, wide: Boolean) {
        val c = colors
        val next = engine.nextStop()
        val drop = lastDrop
        val ordered = riders.sortedBy { it.seat.ordinal }
        fun head(eyebrow: String, title: String, sub: String? = null) {
            v.addView(text(eyebrow, 28f, 400, c.textSecondary))
            v.addView(text(title, if (wide) 64f else 52f, 700, c.textPrimary).apply { maxLines = 2 }, lp(wrap, wrap, top = 8))
            if (sub != null) v.addView(text(sub, 36f, 400, c.textSecondary), lp(wrap, wrap, top = 8))
        }
        fun label(s: String) = v.addView(text(s, 28f, 400, c.textSecondary), lp(wrap, wrap, top = 40))
        fun hint(s: String) = v.addView(text(s, 28f, 400, c.textSecondary), lp(wrap, wrap, top = 24))
        fun bottom(view: View) {
            if (wide) v.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            v.addView(view, lp(match, dp(112), top = 32))
        }
        fun driving() {
            val off = gettingOff()
            head("다음 정차지", next.name, if (off.isEmpty()) whoGetsOff() else null)
            if (off.isNotEmpty()) v.addView(hero(off, wide), lp(match, wrap, top = 40))
            val after = engine.route().drop(1).firstOrNull() ?: return
            label("그다음")
            val then = riders.filter { it.stop.name == after && dropOf(it) == null }
            v.addView(
                infoRow(then.firstOrNull()?.let { looks[it.name] }, after,
                    if (then.isEmpty()) "모두 내린 뒤에 가요" else "${then.joinToString(", ") { it.name }} 하차", 144),
                lp(match, dp(144), top = 16)
            )
        }
        when {
            !parked -> {
                val still = engine.stillOnboard
                val open = engine.openDoorsWhileMoving()
                when {
                    open.isNotEmpty() -> {
                        head("달리는 중", "문을 닫아 주세요")
                        for (r in ordered.filter { it.seat in open })
                            v.addView(kidRow(r, r.name, "${seatText(r)} 문이 열려 있어요"), lp(match, dp(184), top = 24))
                        hint("안전한 곳에 세운 뒤 문을 닫아 주세요.")
                    }
                    still.isNotEmpty() -> {
                        val names = still.joinToString(", ") { it.name }
                        head(still[0].stop.name, "$names${josa(names, "이", "가")} 내리지 않았어요")
                        for (r in still) v.addView(kidRow(r, r.name, "${seatText(r)}에 앉아 있어요"), lp(match, dp(184), top = 24))
                        hint("안전한 곳에 세운 뒤 확인해 주세요.")
                    }
                    else -> driving()
                }
            }
            !started -> {
                val boarded = engine.onboard().map { it.name }
                head("오늘 라이딩", "학원 ${riders.map { it.stop }.distinct().size}곳 들렀다 집으로")
                label("탑승 확인")
                for (r in ordered) {
                    val on = r.name in boarded
                    v.addView(kidRow(r, r.name, seatText(r), if (on) "자리에 앉았어요" else "기다리는 중", on, dim = !on),
                        lp(match, dp(184), top = 16))
                }
                hint(when {
                    !naviReady -> "내비를 연결하고 있어요."
                    boarded.size == riders.size -> "모두 탔어요. 출발할 수 있어요."
                    else -> "아이들이 자리에 앉으면 표시돼요."
                })
                bottom(button("출발", true, naviReady) { startRelay() })
            }
            engine.allDropped() -> {
                head("오늘 라이딩 끝", "모두 잘 내렸어요", "집으로 갈게요")
                for (r in ordered) {
                    val d = dropOf(r)
                    v.addView(kidRow(r, r.name, r.stop.name, d?.let { "${clock.format(Date(it.timeMs))} 하차 확인" }, true),
                        lp(match, dp(184), top = if (r == ordered[0]) 40 else 16))
                }
            }
            drop != null -> {
                val r = riders.first { it.name == drop.rider }
                head(drop.stop, "${drop.rider} 잘 내렸어요", "다음은 ${next.name}")
                v.addView(kidRow(r, r.name, "내렸어요, ${clock.format(Date(drop.timeMs))}"), lp(match, dp(184), top = 40))
                label("하차 확인"); v.addView(steps(r), lp(match, dp(132), top = 16))
                hint(if (engine.isDoorOpen(r.seat)) "문을 닫으면 출발할 수 있어요." else "이제 출발해도 돼요.")
            }
            engine.askingAbout().isNotEmpty() -> {
                // 문을 닫았는데 그대로 앉아 있으면 출발하기 전에 먼저 묻는다
                val ask = engine.askingAbout()
                val names = ask.joinToString(", ") { it.name }
                head(ask[0].stop.name, "$names${josa(names, "이", "가")} 내렸나요?")
                for (r in ask) v.addView(kidRow(r, r.name, "${seatText(r)}에 앉아 있어요"), lp(match, dp(184), top = 24))
                label("하차 확인"); v.addView(steps(ask[0]), lp(match, dp(132), top = 16))
                hint("아이가 내린 것을 직접 본 뒤에 눌러 주세요.")
                bottom(button("내렸어요", true, true) { confirm(ask) })
            }
            riders.any { engine.canConfirm(it) } -> {
                // 도착: 누가 내릴 차례인지와 부모가 남긴 전달 메모를 보여 준다
                val off = riders.filter { engine.canConfirm(it) }
                v.addView(text("${off[0].stop.name} 도착", 32f, 400, c.textSecondary))
                v.addView(turnHero(off, wide), lp(match, wrap, top = 24))
                val note = off.joinToString(" ") { it.note }.trim()
                if (note.isNotEmpty()) v.addView(row().apply {
                    background = round(c.surface, 44); setPadding(dp(32), dp(22), dp(32), dp(22))
                    addView(MarkView(DOT_POS), LinearLayout.LayoutParams(dp(24), dp(24)))
                    addView(text("전달 메모: $note", 32f, 600, c.textPrimary).apply { maxLines = 2 }, lp(wrap, wrap, start = 20))
                }, lp(match, wrap, top = 32))
                label("하차 확인"); v.addView(steps(off[0]), lp(match, dp(132), top = 16))
                hint("아이가 내리면 자동으로 확인돼요.")
                bottom(button("직접 확인했어요", false, true) { confirm(off) })
            }
            else -> driving()
        }
    }

    /** 센서가 틀렸을 때(가방이 자리에 남은 경우 등) 정차 중에 운전자가 직접 확인한다. */
    private fun confirm(list: List<Rider>) {
        for (r in list) onDrop(engine.confirmDrop(r, System.currentTimeMillis()))
    }

    /** 주행 중 화면의 가운데: 이번 정차지에서 내릴 아이. */
    private fun hero(off: List<Rider>, wide: Boolean): View = row().apply {
        val c = colors
        val d = if (wide) 232 else 184
        background = round(c.surface, 36); setPadding(dp(48), dp(if (wide) 64 else 48), dp(48), dp(if (wide) 64 else 48))
        addView(AvatarView(looks[off[0].name]), LinearLayout.LayoutParams(dp(d), dp(d)))
        addView(col().apply {
            addView(text("여기서 내려요", 28f, 400, c.textSecondary))
            addView(text(off.joinToString(", ") { it.name }, if (wide) 96f else 80f, 700, c.textPrimary))
            addView(text(off.joinToString(", ") { seatText(it) }, if (wide) 36f else 30f, 400, c.textSecondary), lp(wrap, wrap, top = 4))
        }, lp(wrap, wrap, start = 44))
    }

    /** 도착 화면: 뒷자리 아이도 알아보도록 프로필과 이름을 가장 크게 보여 준다. */
    private fun turnHero(off: List<Rider>, wide: Boolean): View = row().apply {
        val c = colors
        val d = if (wide) 300 else 220
        addView(AvatarView(looks[off[0].name]), LinearLayout.LayoutParams(dp(d), dp(d)))
        addView(col().apply {
            addView(text(off.joinToString(", ") { it.name }, if (wide) 136f else 104f, 700, c.textPrimary))
            addView(text("내릴 차례예요", if (wide) 56f else 44f, 600, c.textPrimary))
        }, lp(wrap, wrap, start = if (wide) 48 else 40))
    }

    private fun kidRow(r: Rider, title: String, sub: String, right: String? = null, check: Boolean = false, dim: Boolean = false) =
        infoRow(looks[r.name], title, sub, 184, right, check, dim)

    private fun infoRow(
        look: Look?, title: String, sub: String, height: Int,
        right: String? = null, check: Boolean = false, dim: Boolean = false, fill: Int = colors.surface
    ): View = row().apply {
        val c = colors
        val big = height >= 180
        background = round(fill, 32); setPadding(dp(24), 0, dp(36), 0)
        if (look != null) addView(AvatarView(look).apply { if (dim) alpha = 0.45f }, LinearLayout.LayoutParams(dp(height - 48), dp(height - 48)))
        addView(col().apply {
            addView(text(title, if (big) 52f else 40f, 700, c.textPrimary))
            addView(text(sub, if (big) 30f else 26f, 400, c.textSecondary), lp(wrap, wrap, top = 4))
        }, lp(0, wrap, 1f, start = 28))
        if (right != null) {
            if (check) addView(MarkView(CHECK), LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(text(right, 30f, 600, if (check) c.textPrimary else c.textSecondary), lp(wrap, wrap, start = 16))
        }
    }

    /**
     * 하차 확인 3단계: 문 열림, 자리 비움, 문 닫힘. 차량의 문과 좌석 신호를 그대로 보여 준다.
     * 문을 닫았는데 아이가 그대로 앉아 있으면 가운데 단계가 빨갛게 바뀐다.
     */
    private fun steps(r: Rider): View = row().apply {
        val c = colors
        val d = dropOf(r)
        val opened = engine.doorOpenedHere(r.seat)
        val open = engine.isDoorOpen(r.seat)
        val items = listOf(
            Triple("문 열림", if (opened || d != null) DONE else RING_POS,
                if (opened) "열렸어요" else if (d != null) "직접 확인" else "기다리는 중"),
            Triple("자리 비움", when { d != null -> DONE; opened && !open -> BAD; opened -> RING_POS; else -> RING_LINE },
                when { d != null -> clock.format(Date(d.timeMs)); opened && !open -> "아직 앉아 있어요"; opened -> "기다리는 중"; else -> "아직" }),
            Triple("문 닫힘", when { d != null && !open -> DONE; d != null -> RING_POS; else -> RING_LINE },
                when { d != null && !open -> "닫혔어요"; d != null -> "기다리는 중"; else -> "아직" })
        )
        items.forEachIndexed { i, (name, state, sub) ->
            addView(row().apply {
                setPadding(dp(24), 0, dp(12), 0)
                when (state) {
                    RING_POS -> { background = round(c.card, 28, c.positive); elevation = dp(6).toFloat() }
                    BAD -> background = round(c.negativeBg, 28, c.negative)
                    else -> background = round(c.surface, 28)
                }
                addView(MarkView(state), LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(col().apply {
                    addView(text(name, 32f, 600, if (state == RING_LINE) c.textSecondary else c.textPrimary))
                    addView(text(sub, 26f, 400, if (state == BAD) c.negativeText else c.textSecondary).apply { maxLines = 1 })
                }, lp(wrap, wrap, start = 16))
            }, lp(0, match, 1f, start = if (i == 0) 0 else 16))
        }
    }

    /** 오늘 정차지 목록과 타고 있는 아이. 지금 가는 곳만 흰 카드로 띄운다. */
    private fun stopsPanel(withRiders: Boolean): View = col().apply {
        val c = colors
        background = round(c.surface, 36); setPadding(dp(16), dp(30), dp(16), dp(28))
        addView(text("오늘 정차지", 28f, 400, c.textSecondary), lp(wrap, wrap, start = 16))
        val now = if (started) engine.nextStop() else null
        class Line(val name: String, val sub: String?, val state: Int)
        val lines = mutableListOf(Line("집 출발", "${riders.joinToString(", ") { it.name }} 탑승", if (started) DONE_DARK else RING_POS))
        for (s in riders.map { it.stop }.distinct()) {
            val rs = riders.filter { it.stop == s }
            lines += Line(s.name, "${rs.joinToString(", ") { it.name }} 하차",
                if (rs.all { dropOf(it) != null }) DONE_DARK else if (s == now) RING_POS else RING_LINE)
        }
        lines += Line("집 도착", null, if (engine.allDropped()) RING_POS else RING_LINE)
        lines.forEachIndexed { i, line ->
            val current = line.state == RING_POS
            addView(row().apply {
                if (current) { background = InsetDrawable(round(c.card, 24), 0, dp(6), 0, dp(6)); elevation = dp(6).toFloat() }
                setPadding(dp(24), 0, dp(24), 0) // 배경을 넣으면 안쪽 여백이 지워지므로 배경 다음에 준다
                addView(MarkView(line.state, lineAbove = i > 0, lineBelow = i < lines.size - 1), LinearLayout.LayoutParams(dp(30), match))
                addView(col().apply {
                    addView(text(line.name, 34f, 600, c.textPrimary))
                    if (line.sub != null) addView(text(line.sub, 26f, 400, c.textSecondary), lp(wrap, wrap, top = 2))
                }, lp(0, wrap, 1f, start = 30))
                addView(text(if (current) "지금" else if (line.state == DONE_DARK) "완료" else "", 28f, if (current) 600 else 400,
                    if (current) c.positiveText else c.textSecondary))
            }, lp(match, dp(120), top = if (i == 0) 16 else 0))
        }
        if (!withRiders) return@apply
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        addView(text("타고 있는 아이", 28f, 400, c.textSecondary), lp(wrap, wrap, top = 24, start = 16))
        val onboard = engine.onboard().map { it.name }
        for (r in riders.sortedBy { it.seat.ordinal }) {
            val d = dropOf(r)
            val on = r.name in onboard
            addView(row().apply {
                setPadding(dp(16), 0, dp(24), 0)
                addView(AvatarView(looks[r.name]).apply { if (!on) alpha = 0.45f }, LinearLayout.LayoutParams(dp(76), dp(76)))
                addView(col().apply {
                    addView(text(r.name, 32f, 600, c.textPrimary))
                    addView(text(d?.let { "내렸어요, ${clock.format(Date(it.timeMs))}" } ?: seatText(r), 26f, 400, c.textSecondary))
                }, lp(0, wrap, 1f, start = 20))
                if (on) addView(MarkView(DOT_ACTIVE), LinearLayout.LayoutParams(dp(14), dp(14)))
                addView(text(if (on) "탑승 중" else if (d != null) "내림" else "기다리는 중", 26f, 400, c.textSecondary), lp(wrap, wrap, start = 12))
            }, lp(match, dp(104)))
        }
    }

    /** 경고 띠: 빨간 바탕에 흰 글자. 색만이 아니라 느낌표와 문장으로도 알린다. */
    private fun banner(message: String): View = row().apply {
        background = round(0xFFD92D20.toInt(), 28); setPadding(dp(28), 0, dp(28), 0)
        addView(MarkView(BANNER), LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(text(message, 38f, 700, 0xFFFFFFFF.toInt()).apply { maxLines = 1 }, lp(wrap, wrap, start = 24))
    }

    /** Pleos 버튼 상호작용: 누르면 95%로 줄어든다. */
    private fun button(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit): View {
        val c = colors
        return text(label, 38f, 600, if (primary) c.buttonLabel else c.textPrimary).apply {
            gravity = Gravity.CENTER
            background = round(if (primary) c.buttonFilled else c.buttonTonal, 28)
            isEnabled = enabled; alpha = if (enabled) 1f else 0.4f
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.scaleX = 0.95f; v.scaleY = 0.95f }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.scaleX = 1f; v.scaleY = 1f }
                }
                false
            }
            setOnClickListener { onClick() }
        }
    }

    /** 받침에 맞는 조사를 고른다. "으로, 로"는 ㄹ 받침도 "로"를 쓴다. */
    private fun josa(word: String, withBatchim: String, without: String): String {
        val last = word.lastOrNull() ?: return without
        if (last !in '가'..'힣') return without
        val jong = (last.code - 0xAC00) % 28
        return if (jong == 0 || (withBatchim == "으로" && jong == 8)) without else withBatchim
    }

    /** 다음 목적지에서 내리는 아이들. */
    private fun gettingOff(): List<Rider> = engine.nextStop().let { next -> engine.onboard().filter { it.stop == next } }

    /** 다음 목적지에서 누가 내리는지 한 줄로 말한다. */
    private fun whoGetsOff(): String {
        val names = gettingOff().joinToString(", ") { it.name }
        if (names.isNotEmpty()) return "$names${josa(names, "이", "가")} 내려요"
        val onboard = engine.onboard().joinToString(", ") { it.name }
        return if (onboard.isEmpty()) "타고 있는 아이가 없어요" else "$onboard 타고 있어요"
    }

    /** 메신저 프로필처럼 동그란 얼굴 그림. 사진은 쓰지 않는다. */
    private inner class AvatarView(private val look: Look?) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val box = RectF()
        override fun onDraw(canvas: Canvas) {
            val l = look ?: Look(colors.surface, colors.buttonFilled, false)
            val u = width / 100f
            val hair = 0xFF3A2A22.toInt()
            fun oval(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
                paint.color = color; box.set(x0 * u, y0 * u, x1 * u, y1 * u); canvas.drawOval(box, paint)
            }
            fun rect(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, color: Int) {
                paint.color = color; box.set(x0 * u, y0 * u, x1 * u, y1 * u); canvas.drawRoundRect(box, r * u, r * u, paint)
            }
            paint.style = Paint.Style.FILL
            clip.reset(); clip.addCircle(width / 2f, width / 2f, width / 2f, Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            canvas.drawColor(l.bg)
            oval(14f, 78f, 86f, 138f, l.shirt)                       // 어깨
            if (l.bob) rect(23f, 26f, 77f, 76f, 20f, hair)           // 단발 뒷머리
            rect(43f, 66f, 57f, 82f, 4f, 0xFFF2C29C.toInt())         // 목
            oval(29f, 28f, 71f, 72f, 0xFFFFD9B8.toInt())             // 얼굴
            canvas.save()                                            // 앞머리
            canvas.clipRect(26f * u, 22f * u, 74f * u, (if (l.bob) 43f else 39f) * u)
            oval(26f, 22f, 74f, 68f, hair)
            canvas.restore()
            oval(39f, 49f, 44f, 55f, 0xFF2B2B2B.toInt()); oval(56f, 49f, 61f, 55f, 0xFF2B2B2B.toInt())
            oval(32f, 57f, 40f, 62f, 0x8CFF8F8F.toInt()); oval(60f, 57f, 68f, 62f, 0x8CFF8F8F.toInt())
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f * u; paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFF8A3B2E.toInt(); box.set(44f * u, 54f * u, 56f * u, 64f * u)
            canvas.drawArc(box, 20f, 140f, false, paint)             // 웃는 입
            canvas.restore()
        }
    }

    /** 작은 상태 표시: 확인 표시, 고리, 느낌표, 점. 정차지 목록에서는 위아래로 이음선을 함께 그린다. */
    private inner class MarkView(
        private val kind: Int, private val lineAbove: Boolean = false, private val lineBelow: Boolean = false
    ) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val c = colors
            val d = width.toFloat()
            val cx = d / 2
            val cy = height / 2f
            val r = d / 2
            paint.style = Paint.Style.FILL; paint.color = c.line
            if (lineAbove) canvas.drawRect(cx - d * 0.05f, 0f, cx + d * 0.05f, cy - r - d * 0.2f, paint)
            if (lineBelow) canvas.drawRect(cx - d * 0.05f, cy + r + d * 0.2f, cx + d * 0.05f, height.toFloat(), paint)
            fun stroke(color: Int, w: Float) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = w; paint.color = color
                paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            }
            fun check(s: Float, color: Int, w: Float) {
                stroke(color, w); path.reset()
                path.moveTo(cx - s * 0.5f, cy); path.lineTo(cx - s * 0.14f, cy + s * 0.36f); path.lineTo(cx + s * 0.5f, cy - s * 0.36f)
                canvas.drawPath(path, paint)
            }
            fun bang(color: Int) {
                stroke(color, d * 0.1f)
                canvas.drawLine(cx, cy - d * 0.2f, cx, cy + d * 0.04f, paint)
                canvas.drawPoint(cx, cy + d * 0.22f, paint)
            }
            when (kind) {
                DONE, DONE_DARK -> {
                    paint.color = if (kind == DONE) c.positive else c.buttonFilled
                    canvas.drawCircle(cx, cy, r, paint); check(d * 0.46f, 0xFFFFFFFF.toInt(), d * 0.1f)
                }
                BAD -> { paint.color = c.negative; canvas.drawCircle(cx, cy, r, paint); bang(0xFFFFFFFF.toInt()) }
                BANNER -> { paint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(cx, cy, r, paint); bang(0xFFD92D20.toInt()) }
                RING_POS, RING_LINE -> {
                    stroke(if (kind == RING_POS) c.positive else c.line, d * 0.13f)
                    canvas.drawCircle(cx, cy, r - d * 0.065f, paint)
                }
                CHECK -> check(d * 0.9f, c.positive, d * 0.16f)
                DOT_POS -> { paint.color = c.positive; canvas.drawCircle(cx, cy, r, paint) }
                DOT_ACTIVE -> { paint.color = c.active; canvas.drawCircle(cx, cy, r, paint) }
            }
        }
    }

    private fun text(value: String, size: Float, weight: Int, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); typeface = font(weight)
        includeFontPadding = false; letterSpacing = -0.02f
        // 자간을 줄이면 폭이 1px 모자라게 재어져 마지막 글자가 다음 줄로 넘어가는 일이 있어 줄바꿈을 직접 막는다
        if ('\n' !in value && value.length <= 12) setSingleLine()
    }

    /** 시스템 UI 글꼴(Asta Sans). Pleos Connect는 이 글꼴을 pretendard 이름의 패밀리로 제공한다. */
    private fun font(weight: Int): Typeface =
        Typeface.create(Typeface.create("pretendard", Typeface.NORMAL), weight, false)

    private fun speak(text: String) {
        Log.i(TAG, "speak: $text")
        if (ttsReady) tts.speak(text, true) else Log.w(TAG, "tts not ready")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Pleos Connect 디자인 가이드의 System Color 토큰을 바탕으로 한 색. 주간과 야간 테마를 모두 둔다. */
    private class Palette(
        val background: Int, val surface: Int, val card: Int, val line: Int,
        val textPrimary: Int, val textSecondary: Int,
        val buttonFilled: Int, val buttonTonal: Int, val buttonLabel: Int,
        val positive: Int, val positiveText: Int, val active: Int,
        val negative: Int, val negativeBg: Int, val negativeText: Int
    )
    private val colors: Palette by lazy {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) Palette(
            background = 0xFF313236.toInt(), surface = 0xFF1D1E21.toInt(), card = 0xFF46484F.toInt(), line = 0xFF5A5C63.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(), textSecondary = 0xFFC9C8C9.toInt(),
            buttonFilled = 0xFF686A72.toInt(), buttonTonal = 0xFF46484F.toInt(), buttonLabel = 0xFFFFFFFF.toInt(),
            positive = 0xFF5AA9FF.toInt(), positiveText = 0xFF5AA9FF.toInt(), active = 0xFF00E074.toInt(),
            negative = 0xFFD92D20.toInt(), negativeBg = 0xFF4A2422.toInt(), negativeText = 0xFFFF8A80.toInt()
        ) else Palette(
            background = 0xFFFFFFFF.toInt(), surface = 0xFFF3F4F6.toInt(), card = 0xFFFFFFFF.toInt(), line = 0xFFD0D3DA.toInt(),
            textPrimary = 0xFF000000.toInt(), textSecondary = 0xFF4F5053.toInt(),
            buttonFilled = 0xFF44464E.toInt(), buttonTonal = 0xFFE9EAEE.toInt(), buttonLabel = 0xFFFFFFFF.toInt(),
            positive = 0xFF0064FF.toInt(), positiveText = 0xFF0064FF.toInt(), active = 0xFF02C265.toInt(),
            negative = 0xFFD92D20.toInt(), negativeBg = 0xFFFDECEA.toInt(), negativeText = 0xFFB42318.toInt()
        )
    }

    companion object {
        private const val TAG = "RidingRelay"
        private const val DONE = 0        // 파란 원과 확인 표시
        private const val DONE_DARK = 1   // 지난 정차지
        private const val RING_POS = 2    // 지금 기다리는 단계
        private const val RING_LINE = 3   // 아직 오지 않은 단계
        private const val BAD = 4         // 빨간 원과 느낌표
        private const val BANNER = 5
        private const val CHECK = 6
        private const val DOT_POS = 7
        private const val DOT_ACTIVE = 8

        // Emulator v2.1.0 + Vehicle SDK 2.3.2: 차량 HAL은 SEAT_OCCUPANCY를 1(빈자리), 2(착석)로 보내는데
        // SDK는 1일 때 true를 돌려준다. 그래서 에뮬레이터에서는 값이 반대로 들어온다(2026-11-15 확인).
        // 실제 차량에서 다시 확인하고, 맞게 들어오면 false로 바꾼다.
        private const val SEAT_VALUE_INVERTED = true
    }
}
