package kr.ac.pbl.busdriver

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("driver", MODE_PRIVATE) }
    private var api: Api? = null
    private var routeNumber = ""
    private var direction = "상행"
    private var selectedRouteId = ""
    private var vehicleId = ""
    private var snapshot: Snapshot? = null
    private var current = 0
    private var phase = "READY"
    private var lastSuccess = "아직 없음"
    private var message = "노선 선택 필요"
    private var foreground = false
    private var busy = false
    private var generation = 0
    private var restorePending = true
    private var lastAdvance = 0L
    private var dialog: AlertDialog? = null
    private val polling = object : Runnable {
        override fun run() {
            if (!foreground) return
            refresh()
            handler.postDelayed(this, 5000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(R.layout.activity_main)
        scaleDashboard(findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
        findViewById<View>(R.id.title).setOnClickListener { settings() }
        findViewById<View>(R.id.route).setOnClickListener { controls() }
        findViewById<View>(R.id.stationCard).setOnClickListener { controls() }
        findViewById<View>(R.id.status).setOnClickListener { details() }
        render()
        handler.post { if (!isFinishing) settings() }
    }
    // 원본 665×483 화면의 공간/글자 비율을 가로 태블릿 크기에 맞춰 확장합니다.
    private fun scaleDashboard(root: View) {
        val config = resources.configuration
        val scale = minOf(config.screenWidthDp / 665f, config.screenHeightDp / 483f).coerceAtLeast(0.75f)
        fun resize(v: View) {
            v.layoutParams?.let { lp ->
                if (lp.width > 0) lp.width = (lp.width * scale).toInt()
                if (lp.height > 0) lp.height = (lp.height * scale).toInt()
                if (lp is android.view.ViewGroup.MarginLayoutParams) {
                    val start = lp.marginStart; val end = lp.marginEnd
                    lp.setMargins((lp.leftMargin * scale).toInt(), (lp.topMargin * scale).toInt(), (lp.rightMargin * scale).toInt(), (lp.bottomMargin * scale).toInt())
                    lp.marginStart = (start * scale).toInt()
                    lp.marginEnd = (end * scale).toInt()
                }
                v.layoutParams = lp
            }
            v.setPadding((v.paddingLeft * scale).toInt(), (v.paddingTop * scale).toInt(), (v.paddingRight * scale).toInt(), (v.paddingBottom * scale).toInt())
            if (v is TextView) v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, v.textSize * scale)
            if (v is RouteTimeline) v.designScale = scale
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) resize(v.getChildAt(i))
        }
        resize(root)
    }
    override fun onStart() {
        super.onStart(); foreground = true
        handler.removeCallbacks(polling); handler.post(polling)
    }
    override fun onStop() {
        foreground = false; generation++; handler.removeCallbacks(polling)
        persist(); super.onStop()
    }
    override fun onDestroy() { dialog?.dismiss(); worker.shutdownNow(); super.onDestroy() }
    private fun show(d: AlertDialog) { dialog?.dismiss(); dialog = d; d.show() }
    private fun text(id: Int, value: String) { findViewById<TextView>(id).text = value }
    private fun clock() = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
    private fun stateKey(s: Snapshot): String {
        val identity = "${api?.baseUrl}|${routeNumber}|${direction}|${vehicleId}|${s.signature}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun persist() {
        val s = snapshot ?: return
        prefs.edit().putString("stateKey", stateKey(s)).putInt("current", current).putString("phase", phase).apply()
    }
    private fun accept(s: Snapshot) {
        s.counts() // 검증이 끝난 전체 스냅샷만 교체합니다.
        if (snapshot?.signature != s.signature) {
            current = 0; phase = "READY"
            if (restorePending && prefs.getString("stateKey", "") == stateKey(s)) {
                current = prefs.getInt("current", 0).coerceIn(s.stops.indices)
                phase = prefs.getString("phase", "READY").takeIf { it in listOf("READY", "RUNNING", "ENDED") } ?: "READY"
            }
            restorePending = false
        }
        snapshot = s; lastSuccess = clock()
        message = if (s.excludedCount > 0) "주의 · ${s.excludedCount}개 구간 제외" else "갱신 $lastSuccess"
        persist(); render()
    }
    private fun <T> background(job: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true; val ticket = generation
        worker.execute {
            val result = runCatching(job)
            handler.post {
                busy = false
                if (ticket == generation && foreground && !isDestroyed) {
                    result.fold(success) { e ->
                        message = when {
                            e is ApiError && e.status == 401 -> "서버가 로그인을 요구합니다. 노선 조회 API 적용을 확인하세요."
                            e is ApiError && e.status == 403 -> "접근 불가: ${e.message}"
                            else -> "갱신 실패 · 마지막 정상 $lastSuccess\n${e.message ?: "네트워크를 확인하세요."}"
                        }
                        if (e is ApiError && e.status in listOf(401, 403)) {
                            persist(); snapshot = null; restorePending = true
                        }
                        render()
                    }
                }
            }
        }
    }
    private fun refresh() {
        val client = api ?: return
        if (selectedRouteId.isBlank() || vehicleId.isBlank()) return
        val selectedNumber = routeNumber
        val selectedDirection = direction
        val route = selectedRouteId; val vehicle = vehicleId
        background({ client.snapshot(selectedNumber, selectedDirection, route, vehicle) }) { accept(it) }
    }

    private fun render() {
        val s = snapshot
        text(R.id.route, s?.let { "${it.routeLabel} · ${it.direction}" } ?: "미선택")
        text(R.id.status, message.lineSequence().first())
        text(R.id.current, s?.stops?.getOrNull(current)?.name ?: "운행 준비")
        text(R.id.next, s?.stops?.getOrNull(current + 1)?.name ?: if (s != null) "종점" else "—")
        val counts = s?.counts()
        text(R.id.boarding, counts?.boarding?.get(current)?.let { "${it}명" } ?: "—")
        text(R.id.alighting, counts?.alighting?.get(current)?.let { "${it}명" } ?: "—")
        findViewById<RouteTimeline>(R.id.timeline).apply { stops = s?.stops ?: emptyList(); this.current = this@MainActivity.current; invalidate() }
    }
    private fun reset() {
        persist(); generation++; api = null; selectedRouteId = ""; vehicleId = ""; snapshot = null
        current = 0; phase = "READY"; restorePending = true; lastSuccess = "아직 없음"
        message = "노선 선택 필요"; render()
    }
    private fun settings() = routeDialog()
    private fun routeDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        box.addView(TextView(this).apply { text = "운행 방향" })
        val directions = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val up = RadioButton(this).apply { id = View.generateViewId(); text = "상행" }
        val down = RadioButton(this).apply { id = View.generateViewId(); text = "하행" }
        directions.addView(up); directions.addView(down)
        directions.check(if (prefs.getString("direction", "상행") == "하행") down.id else up.id)
        box.addView(directions)
        val number = EditText(this).apply {
            hint = "노선번호 (예: 150, 150-1)"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("routeNumber", "")); box.addView(this)
        }
        val d = AlertDialog.Builder(this).setTitle("운행 노선 선택").setView(box)
            .setPositiveButton("선택 완료", null).setNeutralButton("서버 설정") { _, _ -> serverDialog() }
            .setNegativeButton("닫기", null).create()
        show(d)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selectedNumber = number.text.toString().trim()
            if (!selectedNumber.matches(Regex("[0-9]{1,6}(-[0-9]{1,3})?"))) {
                number.error = "노선번호를 입력하세요. 예: 150 또는 150-1"; return@setOnClickListener
            }
            if (busy) { Toast.makeText(this, "조회가 끝난 뒤 다시 선택하세요.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            reset(); routeNumber = selectedNumber
            direction = if (directions.checkedRadioButtonId == down.id) "하행" else "상행"
            prefs.edit().putString("routeNumber", routeNumber).putString("direction", direction).apply()
            api = Api(prefs.getString("url", "http://10.0.2.2:3000")!!)
            message = "${routeNumber}번 $direction 조회 중…"; render(); d.dismiss(); loadRoutes()
        }
    }
    private fun loadRoutes() {
        val client = api ?: return
        background({ client.routes(routeNumber, direction) }) { choices ->
            if (choices.isEmpty()) { message = "해당 노선이 없습니다. 노선번호를 확인하세요."; render(); return@background }
            fun choose(choice: Api.Choice) { selectedRouteId = choice.id; loadVehicles() }
            if (choices.size == 1) choose(choices.first())
            else show(AlertDialog.Builder(this).setTitle("지원 노선 선택")
                .setItems(choices.map { it.label }.toTypedArray()) { _, i -> choose(choices[i]) }
                .setNegativeButton("취소") { _, _ -> reset() }.create())
        }
    }
    private fun loadVehicles() {
        val client = api ?: return
        message = "운행 차량 조회 중…"; render()
        background({ client.vehicles(routeNumber, direction, selectedRouteId) }) { choices ->
            if (choices.isEmpty()) { message = "조회된 운행 차량이 없습니다. 잠시 후 노선을 다시 선택하세요."; render(); return@background }
            show(AlertDialog.Builder(this).setTitle("운행할 차량 선택 · $direction")
                .setItems(choices.map { it.label }.toTypedArray()) { _, i ->
                    vehicleId = choices[i].id
                    message = "예약 조회 중…"; render(); refresh()
                }.setNegativeButton("취소") { _, _ -> reset() }.create())
        }
    }
    private fun serverDialog() {
        val url = EditText(this).apply {
            hint = "서버 URL"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("url", "http://10.0.2.2:3000"))
        }
        val d = AlertDialog.Builder(this).setTitle("서버 연결 설정").setView(url)
            .setPositiveButton("저장", null).setNegativeButton("취소") { _, _ -> routeDialog() }.create()
        show(d)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val base = url.text.toString().trim().trimEnd('/')
            val valid = runCatching {
                val u = URI(base)
                require(u.scheme in listOf("http", "https") && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.query == null && u.fragment == null && u.path.isNullOrEmpty())
                require(BuildConfig.DEBUG || u.scheme == "https")
            }.isSuccess
            if (!valid) { url.error = "올바른 서버 주소를 입력하세요."; return@setOnClickListener }
            reset(); prefs.edit().putString("url", base).apply(); d.dismiss(); routeDialog()
        }
    }
    private fun controls() {
        val s = snapshot ?: run { settings(); return }
        val state = when (phase) { "RUNNING" -> "운행중"; "ENDED" -> "종료"; else -> "미시작" }
        val items = mutableListOf("운행 시작 / 처음부터", "다음 정류장으로 진행", "운행 종료", "정류장별 예약 보기")
        show(AlertDialog.Builder(this).setTitle("${s.routeLabel} · ${s.direction} · $vehicleId · $state")
            .setItems(items.toTypedArray()) { _, which -> when (which) {
                0 -> show(AlertDialog.Builder(this).setTitle("운행을 시작할까요?").setMessage("첫 정류장부터 수동 진행합니다. 실제 예약 상태는 변경하지 않습니다.")
                    .setPositiveButton("시작") { _, _ -> current = 0; phase = "RUNNING"; persist(); render() }.setNegativeButton("취소", null).create())
                1 -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (phase != "RUNNING") Toast.makeText(this, "먼저 운행을 시작하세요.", Toast.LENGTH_SHORT).show()
                    else if (current >= s.stops.lastIndex) Toast.makeText(this, "종점입니다. 운행을 종료하세요.", Toast.LENGTH_SHORT).show()
                    else if (now - lastAdvance > 800) { current++; lastAdvance = now; persist(); render() }
                }
                2 -> { phase = "ENDED"; persist(); render(); Toast.makeText(this, "로컬 운행 종료", Toast.LENGTH_SHORT).show() }
                3 -> details()
            } }.setNegativeButton("닫기", null).create())
    }
    private fun details() {
        val s = snapshot; val c = s?.counts()
        val rows = s?.stops?.joinToString("\n") { "${it.sequence + 1}. ${it.name}   승차 ${c!!.boarding[it.sequence]} · 하차 요청 ${c.alighting[it.sequence]} · 예정 ${c.plannedAlighting.getOrNull(it.sequence) ?: 0}" } ?: "정상 조회 데이터가 없습니다."
        show(AlertDialog.Builder(this).setTitle("예약·갱신 정보")
            .setMessage("차량: $vehicleId\n$message\n마지막 정상 조회: $lastSuccess\n제외 구간: ${s?.excludedCount ?: "미확인"}\n\n$rows\n\n승차는 노선·방향별 대기 인원, 하차는 선택 차량의 명시적 하차 요청입니다. 탑승·하차 상태는 승객의 앱 조작 기준입니다.")
            .setPositiveButton("닫기", null).setNeutralButton("다시 조회") { _, _ -> refresh() }.create())
    }
}
