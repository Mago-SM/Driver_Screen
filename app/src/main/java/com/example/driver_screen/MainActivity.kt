package com.example.driver_screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.driver_screen.ui.theme.Driver_ScreenTheme

private val Blue = Color(0xFF1967D2)
private val Red = Color(0xFFBD3027)
private val Ink = Color(0xFF202124)

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
    var noticeVisible by rememberSaveable { mutableStateOf(true) }
    var advanceVisible by rememberSaveable { mutableStateOf(false) }
    val trip = DriverTrip(index, acknowledged)
    Surface(color = Color(0xFFF1F3F5), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().background(Ink).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("진주시 시내버스 운행 시스템", color = Color.White,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text("150번", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("운전자 화면 · 미리보기", color = Ink, fontWeight = FontWeight.Bold)
                Text("예시 데이터입니다. 실제 위치·예약 서버는 아직 연결되지 않았습니다.",
                    color = Color(0xFF535B66), fontSize = 14.sp)
                BoxWithConstraints {
                    if (maxWidth >= 600.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StopPanel(trip, Modifier.weight(1f))
                            ReservationPanel(trip, { noticeVisible = true }, Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            StopPanel(trip, Modifier.fillMaxWidth())
                            ReservationPanel(trip, { noticeVisible = true }, Modifier.fillMaxWidth())
                        }
                    }
                }
                Text("운행 위치 · 예시", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DriverTrip.stops.forEachIndexed { stopIndex, stop ->
                        val current = stopIndex == index
                        val next = stopIndex == index + 1
                        Surface(color = if (current) Color(0xFFE7EFFF) else Color.White,
                            shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.width(140.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stop.name, color = Ink)
                                Text(when { current -> "● 현재"; next -> "● 다음"; stopIndex < index -> "통과"; else -> "예정" },
                                    color = if (current) Blue else if (next) Red else Color(0xFF535B66))
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { advanceVisible = true }, enabled = trip.next != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("미리보기: 다음 정류장으로 이동")
                }
            }
        }
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
    Surface(modifier, color = Color.White, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("현재 정류장", color = Color(0xFF535B66))
            Text(trip.current.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("다음 정류장", color = Red)
            Text(trip.next?.name ?: "다음 정류장 없음", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}

@Composable
private fun ReservationPanel(trip: DriverTrip, onNotice: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("다음 정류장 예약 현황", color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CountPanel("승차 예약", trip.next?.boarding ?: 0, Blue, Color(0xFFE7EFFF), Modifier.weight(1f))
                CountPanel("하차 예약", trip.next?.alighting ?: 0, Red, Color(0xFFFCE8E6), Modifier.weight(1f))
            }
            Text(when { trip.next == null -> "종점입니다. 예시 운행이 끝났습니다."
                !trip.needsStop -> "다음 정류장 예약 없음"
                trip.acknowledged -> "정차 안내 확인됨 · 예약 인원은 유지됩니다."
                else -> "다음 정류장 정차 필요" }, color = Ink)
            Button(onClick = onNotice, enabled = trip.needsStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (trip.acknowledged) "정차 안내 다시 보기" else "정차 안내 보기")
            }
        }
    }
}

@Composable
private fun CountPanel(label: String, count: Int, color: Color, background: Color, modifier: Modifier) {
    Surface(modifier, color = background, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(vertical = 24.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = color)
            Text("${count}명", color = color, fontSize = 42.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 600)
@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun DriverScreenPreview() { Driver_ScreenTheme { DriverScreen() } }
