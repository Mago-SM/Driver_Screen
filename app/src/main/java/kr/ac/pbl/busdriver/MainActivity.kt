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
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock


class MainActivity : Activity() {

    private fun startGps() {
        if (!foreground || isDestroyed) return
        val fine = checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1001
            )
            return
        }

        try {
            if (!locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER
                )) {
                Toast.makeText(
                    this,
                    "기기 GPS를 켜주세요.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,
                0f,
                gpsListener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "GPS 권한이 없습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 1001 &&
            checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startGps()
        }
    }

    private fun sendGpsToServer() {
        if (!foreground || locationSending) return
        val client = api ?: return

        if (selectedRouteId.isBlank() || vehicleId.isBlank()) {
            return
        }

        val location = latestLocation?.let { Location(it) } ?: return

        // 오래된 GPS 좌표 전송 방지
        val age = SystemClock.elapsedRealtimeNanos() -
            location.elapsedRealtimeNanos

        if (age < 0 || age > 15_000_000_000L) return

        if (!location.hasAccuracy() ||
            !location.accuracy.isFinite() || location.accuracy !in 0f..100f) return

        val now = SystemClock.elapsedRealtime()

        if (now - lastLocationSentAt < 5000L) return
        lastLocationSentAt = now

        val route = selectedRouteId
        val vehicle = vehicleId

        locationSending = true
        locationWorker.execute {
            try {
                client.sendLocation(
                    route,
                    vehicle,
                    location.latitude,
                    location.longitude,
                    location.accuracy
                )

                android.util.Log.d(
                    "DRIVER_GPS",
                    "GPS 전송 성공"
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "DRIVER_GPS",
                    "GPS 전송 실패",
                    e
                )
            } finally {
                handler.post { locationSending = false }
            }
        }
    }

    private val locationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }
    private val locationWorker = Executors.newSingleThreadExecutor()
    private var locationSending = false
    private var latestLocation: Location? = null
    private var lastLocationSentAt = 0L
    private val gpsListener = LocationListener { location ->
        latestLocation = location
    }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("driver", MODE_PRIVATE) }
    private var api: Api? = null
    private var routeNumber = ""
    private var direction = "상행"
    private var selectedRouteId = ""
    private var vehicleId = ""
    private var snapshot: Snapshot? = null
    private val gps = GpsProgress()
    private var arrivalCard: LinearLayout? = null
    private var arrivalOverlay: FrameLayout? = null
    private var arrivalName: TextView? = null
    private var arrivalDistance: TextView? = null
    private var arrivalBoarding: TextView? = null
    private var arrivalAlighting: TextView? = null
    private var arrivalPulse: android.animation.ObjectAnimator? = null
    private var lastPulsedArrival: String? = null
    private val expireGps = Runnable { gps.unavailable(); render() }
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
            sendGpsToServer()
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
        createArrivalCard()
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
        startGps()
        handler.removeCallbacks(polling); handler.post(polling)
    }
    override fun onStop() {
        foreground = false; generation++; handler.removeCallbacks(polling)
        locationManager.removeUpdates(gpsListener)
        latestLocation = null
        handler.removeCallbacks(expireGps); gps.unavailable(); hideArrival()
        persist(); super.onStop()
    }
    override fun onDestroy() {
        arrivalPulse?.cancel(); dialog?.dismiss()
        locationWorker.shutdownNow(); worker.shutdownNow(); super.onDestroy()
    }
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
            gps.reset(); current = 0; phase = "READY"
            if (restorePending && prefs.getString("stateKey", "") == stateKey(s)) {
                current = prefs.getInt("current", 0).coerceIn(s.stops.indices)
                phase = prefs.getString("phase", "READY").takeIf { it in listOf("READY", "RUNNING", "ENDED") } ?: "READY"
            }
            restorePending = false
        }
        snapshot = s; lastSuccess = clock()
        gps.update(s.stops, s.position, System.currentTimeMillis())
        if (gps.live) { current = gps.current; phase = "RUNNING" }
        handler.removeCallbacks(expireGps)
        s.position?.let { handler.postDelayed(expireGps, (it.observedAt + 30001 - System.currentTimeMillis()).coerceIn(1L, 35001L)) }
        message = if (s.excludedCount > 0) "주의 · ${s.excludedCount}개 구간 제외" else "갱신 $lastSuccess"
        if (!gps.live) message += " · GPS 대기"
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
                        gps.unavailable(); handler.removeCallbacks(expireGps)
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
        renderArrival()
        val s = snapshot
        text(R.id.route, s?.let { "${it.routeLabel} · ${it.direction}" } ?: "미선택")
        text(R.id.status, message.lineSequence().first())
        text(R.id.current, s?.stops?.getOrNull(current)?.name ?: "운행 준비")
        text(R.id.next, s?.stops?.getOrNull(current + 1)?.name ?: if (s != null) "종점" else "—")
        val counts = s?.counts()
        val alighting = counts?.let { it.plannedAlighting.ifEmpty { it.alighting } }
        val service = gps.serviceSequence ?: current
        val serviceName = s?.stops?.getOrNull(service)?.name
        text(R.id.countsStop, serviceName?.let { "$it · 승하차 인원" } ?: "승하차 인원")
        text(R.id.boarding, counts?.boarding?.getOrNull(service)?.let { "${it}명" } ?: "—")
        text(R.id.alighting, alighting?.getOrNull(service)?.let { "${it}명" } ?: "—")
        findViewById<RouteTimeline>(R.id.timeline).apply { stops = s?.stops ?: emptyList(); this.current = this@MainActivity.current; invalidate() }
    }
    private fun createArrivalCard() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun color(value: String) = android.graphics.Color.parseColor(value)
        fun surface(fill: String, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color(fill)); cornerRadius = dp(radius).toFloat()
        }
        fun label(size: Float, ink: String, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(color(ink)); includeFontPadding = false
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(color("#800B1220"))
            visibility = View.GONE
            isClickable = true
            elevation = dp(20).toFloat()
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(28))
            background = surface("#FFFFFF", 24)
            elevation = dp(12).toFloat()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(label(18f, "#2563EB", true).apply { text = "●  곧 도착" },
            LinearLayout.LayoutParams(0, -2, 1f))
        arrivalDistance = label(16f, "#475569", true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = surface("#F1F5F9", 10)
        }
        header.addView(arrivalDistance)
        card.addView(header)
        arrivalName = label(40f, "#0F172A", true).apply {
            maxLines = 2
            setAutoSizeTextTypeUniformWithConfiguration(26, 40, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        card.addView(arrivalName, LinearLayout.LayoutParams(-1, dp(100)).apply {
            topMargin = dp(12); bottomMargin = dp(20)
        })
        arrivalName?.gravity = android.view.Gravity.CENTER_VERTICAL
        val countsRow = LinearLayout(this)
        fun countTile(title: String, backgroundColor: String, ink: String): Pair<LinearLayout, TextView> {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = surface(backgroundColor, 16)
            }
            tile.addView(label(17f, ink, true).apply { text = title })
            val value = label(34f, ink, true)
            tile.addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            return tile to value
        }
        val (boardingTile, boardingValue) = countTile("↑  승차", "#EFF6FF", "#1D4ED8")
        val (alightingTile, alightingValue) = countTile("↓  하차", "#FFF1F2", "#E11D48")
        arrivalBoarding = boardingValue; arrivalAlighting = alightingValue
        countsRow.addView(boardingTile, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        countsRow.addView(alightingTile, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        card.addView(countsRow)
        overlay.addView(card, FrameLayout.LayoutParams(
            minOf(dp(640), (resources.displayMetrics.widthPixels * 0.76f).toInt()),
            -2, android.view.Gravity.CENTER))
        findViewById<FrameLayout>(android.R.id.content).addView(overlay, FrameLayout.LayoutParams(-1, -1))
        arrivalOverlay = overlay; arrivalCard = card
    }
    private fun hideArrival() {
        arrivalPulse?.cancel(); arrivalPulse = null
        arrivalCard?.alpha = 1f
        arrivalOverlay?.visibility = View.GONE
    }
    private fun renderArrival() {
        val s = snapshot
        val target = gps.alertSequence
        if (!foreground || !gps.live || s == null || target == null || target !in s.stops.indices) {
            hideArrival(); return
        }
        val counts = s.counts()
        val alighting = counts.plannedAlighting.ifEmpty { counts.alighting }
        arrivalName?.text = s.stops[target].name
        arrivalDistance?.text = "약 ${kotlin.math.round(gps.distanceMeters ?: 20.0).toInt()}m"
        arrivalBoarding?.text = "${counts.boarding[target]}명"
        arrivalAlighting?.text = "${alighting[target]}명"
        arrivalOverlay?.visibility = View.VISIBLE
        val key = "${s.signature}|$vehicleId|$target"
        if (lastPulsedArrival != key) {
            lastPulsedArrival = key
            arrivalPulse?.cancel()
            // Three gentle pulses on first arrival only, never on each 5-second refresh.
            arrivalPulse = android.animation.ObjectAnimator.ofFloat(arrivalCard, View.ALPHA, 1f, 0.58f, 1f).apply {
                duration = 1600
                repeatCount = 2
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }
    }
    private fun reset() {
        persist(); generation++; api = null; selectedRouteId = ""; vehicleId = ""; snapshot = null
        gps.reset(); lastPulsedArrival = null; handler.removeCallbacks(expireGps)
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
        if (gps.live) { details(); return }
        val state = when (phase) { "RUNNING" -> "운행중"; "ENDED" -> "종료"; else -> "미시작" }
        val items = mutableListOf("운행 시작 / 처음부터", "다음 정류장으로 진행", "운행 종료", "정류장별 예약 보기")
        show(AlertDialog.Builder(this).setTitle("${s.routeLabel} · ${s.direction} · $vehicleId · $state")
            .setItems(items.toTypedArray()) { _, which -> when (which) {
                0 -> show(AlertDialog.Builder(this).setTitle("운행을 시작할까요?").setMessage("첫 정류장부터 수동 진행합니다. 실제 예약 상태는 변경하지 않습니다.")
                    .setPositiveButton("시작") { _, _ -> gps.reset(); current = 0; phase = "RUNNING"; persist(); render() }.setNegativeButton("취소", null).create())
                1 -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (phase != "RUNNING") Toast.makeText(this, "먼저 운행을 시작하세요.", Toast.LENGTH_SHORT).show()
                    else if (current >= s.stops.lastIndex) Toast.makeText(this, "종점입니다. 운행을 종료하세요.", Toast.LENGTH_SHORT).show()
                    else if (now - lastAdvance > 800) { gps.reset(); current++; lastAdvance = now; persist(); render() }
                }
                2 -> { gps.reset(); phase = "ENDED"; persist(); render(); Toast.makeText(this, "로컬 운행 종료", Toast.LENGTH_SHORT).show() }
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
