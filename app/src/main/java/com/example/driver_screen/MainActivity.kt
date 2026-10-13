package com.example.driver_screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.driver_screen.ui.theme.Driver_ScreenTheme
import kotlinx.coroutines.delay

private val Blue = Color(0xFF1971EF)
private val Red = Color(0xFFF23C30)
private val Ink = Color(0xFF202124)
private val Grey = Color(0xFF62666D)
private val PaleBlue = Color(0xFFE7EFFF)
private val PaleRed = Color(0xFFFCE8E6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Driver_ScreenTheme { DriverScreen() } }
    }
}

@Composable
fun DriverScreen() {
    var index by rememberSaveable { mutableIntStateOf(1) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var noticeVisible by rememberSaveable { mutableStateOf(false) }
    var advanceVisible by rememberSaveable { mutableStateOf(false) }
    var demoVisible by rememberSaveable { mutableStateOf(false) }
    var clock by remember { mutableStateOf(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            delay(1000)
        }
    }
    val trip = DriverTrip(index, acknowledged)
    Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            val wide = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(Ink).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("진주시 시내버스 운행 시스템", color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(12.dp))
                    Surface(color = Blue, shape = RoundedCornerShape(4.dp), modifier = Modifier.combinedClickable(
                        onClick = { demoVisible = true }, onLongClick = { demoVisible = true })) {
                        Text("150번", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(clock, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                if (wide) {
                    Row(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF0F2F4)).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StopPanel(trip, Modifier.weight(1f).fillMaxHeight())
                        ReservationPanel(trip, { noticeVisible = true }, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF0F2F4))
                        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        StopPanel(trip, Modifier.fillMaxWidth().heightIn(min = 240.dp))
                        ReservationPanel(trip, { noticeVisible = true }, Modifier.fillMaxWidth().height(280.dp))
                    }
                }
                RouteProgress(trip, Modifier.fillMaxWidth().height(86.dp))
            }
        }
    }
    if (demoVisible) {
        AlertDialog(onDismissRequest = { demoVisible = false }, title = { Text("미리보기 설정") },
            text = { Text("현재 화면은 예시 데이터입니다. 위치·예약 서버는 아직 연결되지 않았습니다.") },
            confirmButton = { TextButton(enabled = trip.next != null, onClick = { demoVisible = false; advanceVisible = true }) { Text("예시 정류장 이동") } },
            dismissButton = { TextButton(onClick = { demoVisible = false }) { Text("닫기") } })
    }
    if (noticeVisible && trip.needsStop) {
        AlertDialog(onDismissRequest = { noticeVisible = false },
            title = { Text("다음 정류장 정차 필요", color = Red, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(trip.next!!.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("승차 예약 ${trip.next!!.boarding}명 · 하차 예약 ${trip.next!!.alighting}명")
                    Text("안전하게 정차한 뒤 확인하세요. 확인은 안내 확인 표시이며 승차·하차 완료 처리는 하지 않습니다.")
                }
            },
            confirmButton = { TextButton(onClick = { acknowledged = trip.acknowledge().acknowledged; noticeVisible = false }) {
                Text("정차 후 확인")
            } },
            dismissButton = { TextButton(onClick = { noticeVisible = false }) { Text("나중에 확인") } })
    }
    if (advanceVisible) {
        AlertDialog(onDismissRequest = { advanceVisible = false }, title = { Text("예시 운행 이동") },
            text = { Text("미리보기 위치만 이동합니다. 실제 운행·예약 정보는 변경하지 않습니다.") },
            confirmButton = { TextButton(onClick = {
                val advanced = trip.advance()
                index = advanced.currentIndex; acknowledged = advanced.acknowledged
                noticeVisible = advanced.needsStop; advanceVisible = false
            }) { Text("예시 이동") } },
            dismissButton = { TextButton(onClick = { advanceVisible = false }) { Text("취소") } })
    }
}

@Composable
private fun StopPanel(trip: DriverTrip, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFDFE2E6))) {
        Column(Modifier.padding(20.dp)) {
            Text("현재 정류장", color = Grey, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(trip.current.name, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Color(0xFFD6D9DF))
            Text("다음 정류장 ▣", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(trip.next?.name ?: "다음 정류장 없음", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        }
    }
}

@Composable
private fun ReservationPanel(trip: DriverTrip, onNotice: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFDFE2E6))) {
        Column(Modifier.padding(20.dp)) {
            Text("실시간 예약 현황", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.weight(1f).fillMaxWidth().clickable(enabled = trip.needsStop, onClickLabel = "정차 안내 보기", onClick = onNotice),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CountPanel("승차 예약", trip.next?.boarding ?: 0, Blue, PaleBlue, Modifier.weight(1f).fillMaxHeight())
                CountPanel("하차 예약", trip.next?.alighting ?: 0, Red, PaleRed, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun CountPanel(label: String, count: Int, color: Color, background: Color, modifier: Modifier) {
    Surface(modifier, color = background, shape = RoundedCornerShape(9.dp)) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("${count}명", color = color, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun RouteProgress(trip: DriverTrip, modifier: Modifier) {
    BoxWithConstraints(modifier.background(Color.White).padding(horizontal = 20.dp, vertical = 12.dp)) {
        val nodeWidth = maxWidth / DriverTrip.stops.size
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val step = size.width / DriverTrip.stops.size
            val start = step / 2
            drawLine(Color(0xFFD9DBE1), Offset(start, 12.dp.toPx()), Offset(size.width - start, 12.dp.toPx()), 4.dp.toPx())
            drawLine(Grey, Offset(start, 12.dp.toPx()), Offset(start + step * trip.currentIndex, 12.dp.toPx()), 4.dp.toPx())
        }
        Row {
            DriverTrip.stops.forEachIndexed { i, stop ->
                val current = i == trip.currentIndex
                val next = i == trip.currentIndex + 1
                val color = when { current -> Blue; next -> Red; i < trip.currentIndex -> Grey; else -> Color(0xFFD9DBE1) }
                Column(Modifier.width(nodeWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(24.dp).background(Color.White)) { drawCircle(color, radius = 7.dp.toPx()) }
                    Text(stop.name, color = if (current) Blue else if (i > trip.currentIndex + 1) Grey else Ink,
                        fontSize = 10.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                    Text(when { current -> "현재"; next -> "다음"; i < trip.currentIndex -> "이전"; else -> "예정" },
                        fontSize = 8.sp, color = Grey, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 720, heightDp = 480)
@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun DriverScreenPreview() { Driver_ScreenTheme { DriverScreen() } }
