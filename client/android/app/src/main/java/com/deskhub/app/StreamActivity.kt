// =============================================================================
// StreamActivity.kt — màn hình XEM + ĐIỀU KHIỂN.
//
// GIAO DIỆN TRẦN, DÙNG THẲNG MATERIAL 3 (2026-07-27)
//   Chrome của màn này từng dựng trên hệ thiết kế riêng (ui/Tokens.kt +
//   ui/Components.kt): HUD kính bo pill, chip trạng thái, chấm sống, biểu đồ RTT.
//   Cả bộ đó đã xoá theo yêu cầu "trông cơ bản thôi, không cần màu mè" — giờ chỉ còn
//   `Text` và `Button` mặc định của Material 3 trên nền đen.
//
// BỐ CỤC: BA HÀNG XẾP DỌC, KHÔNG CHỒNG NHAU
//     [thanh trên]  địa chỉ + dòng số liệu
//     [ô giữa]      video (weight 1f) + trackpad phủ đúng ô này
//     [thanh dưới]  phím tắt + Display/Keyboard/End
//   Hai thanh từng là HUD nổi ĐÈ lên video; tách hẳn ra 2026-07-27 theo yêu cầu.
//   Đánh đổi: khung hình mất đúng phần chiều cao của hai thanh, đổi lại không còn gì
//   nằm chắn lên nội dung đang xem, và rê tay lên thanh nút không làm con trỏ nhảy.
//
//   Phần CHỨC NĂNG giữ nguyên từng dòng: SurfaceView, tỉ lệ khung/letterbox, trackpad
//   ảo, view hứng phím IME, hàng phím tắt. Chúng không phải trang trí.
//
// NHIỀU MÀN HÌNH: ĐỔI NGAY TẠI ĐÂY
//   Host chia sẻ TẤT CẢ màn hình, nên máy nhiều monitor gửi về nhiều nguồn. Nút
//   "Display" ở thanh dưới (chỉ hiện khi có >1 nguồn) mở danh sách và đổi tại chỗ —
//   xem StreamActivity.switchSource về lý do đổi = đóng phiên + mở phiên mới.
//
// ZOOM (2026-07-29)
//   Hai ngón để phóng to và rê khung nhìn. Toàn bộ trạng thái + phép toán nằm ở
//   VideoZoom.kt (VideoTransform), dùng CHUNG cho ô video và trackpad — hai bên tự
//   tính riêng là lệch nhau ngay. Ô giữa đo viewport một lần rồi truyền xuống cả hai.
//
// ĐIỀU QUAN TRỌNG NHẤT (không đổi): KHUNG HÌNH KHÔNG ĐI QUA COMPOSE
//   Compose chỉ lo phần chrome. Pixel của video đi thẳng từ bộ giải mã phần cứng ra
//   màn hình qua hardware composer — SurfaceView chứ không phải TextureView
//   (TextureView đi qua view hierarchy, thêm một lần copy GPU và một frame trễ).
//
// VÒNG ĐỜI SURFACE LÀ PHẦN DỄ SAI NHẤT
//   surfaceCreated  → giao Surface xuống C++.
//   surfaceDestroyed → thu hồi, và lời gọi này CHẶN tới khi bộ giải mã buông ra.
//   Thứ tự đó bắt buộc: hàm surfaceDestroyed trả về là hệ điều hành hủy Surface
//   thật, codec còn vẽ vào đó là lỗi dùng-sau-giải-phóng.
//
// VÌ SAO HỎI TRẠNG THÁI THEO NHỊP THAY VÌ ĐỂ C++ GỌI NGƯỢC LÊN
//   Gọi ngược từ C++ vào JVM đòi gắn thread vào JVM và giữ global ref, mỗi frame.
//   Hỏi 500 ms một lần rẻ hơn nhiều, mà dòng số liệu chỉ đổi mỗi giây một lần.
//
// LIÊN QUAN: MainActivity.kt (nơi mở màn hình này), NativeClient.kt, ClientLoop.h
// =============================================================================
package com.deskhub.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class StreamActivity : ComponentActivity() {
    // Thế hệ phiên do nativeStart trả về (0 = không mở được). Tầng native là
    // singleton mà vòng đời hai StreamActivity có thể chồng lấn nhau (kết thúc rồi
    // kết nối lại ngay) — giữ thế hệ để onDestroy trễ của instance này không giết
    // nhầm phiên mà instance mới vừa mở.
    //
    // Là state của Compose vì đổi màn hình giữa chừng sẽ thay nó bằng một thế hệ mới,
    // và giao diện phải vẽ lại theo (xoá số liệu cũ, poll lại từ đầu).
    private var session by mutableStateOf(0L)

    // Nguồn đang xem + danh sách host đang chia sẻ (do MainActivity truyền sang, xem
    // openStream). Rỗng hoặc một phần tử = không có gì để đổi, nút Display ẩn.
    private var currentSourceId by mutableIntStateOf(0)
    private var sources: List<NativeClient.Source> = emptyList()
    private var address = ""

    // Giữ ở Activity chứ không tạo trong composable: callback này phải sống đúng
    // bằng vòng đời SurfaceView, không được dựng lại theo mỗi lần recomposition.
    private val holderCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                NativeClient.nativeSetSurface(holder.surface)
            }

            override fun surfaceChanged(
                h: SurfaceHolder,
                f: Int,
                w: Int,
                ht: Int,
            ) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Chặn tới khi bộ giải mã buông surface — xem chú thích ở NativeClient.
                // Bản có so danh tính: surfaceDestroyed trễ của activity cũ không
                // được giật cửa sổ mà phiên mới đang vẽ vào.
                NativeClient.nativeReleaseSurface(holder.surface)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Người xem không chạm màn hình trong lúc xem, nên nếu không giữ cờ này thì
        // máy tự tắt màn hình giữa chừng — kéo theo Surface bị hủy và phiên đứt.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        address = intent.getStringExtra("addr").orEmpty()
        // Không có "source" (vd. chạy thẳng từ adb) -> nguồn 0, như trước.
        currentSourceId = intent.getIntExtra("source", 0)
        sources = readSources(intent)
        session = NativeClient.nativeStart(address, currentSourceId)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StreamScreen(
                    address = address,
                    sessionKey = session,
                    sources = sources,
                    currentSourceId = currentSourceId,
                    holderCallback = holderCallback,
                    onSwitchSource = ::switchSource,
                    onDismiss = { finish() },
                )
            }
        }
    }

    // Bốn mảng song song do MainActivity gói lại — xem MainActivity.openStream.
    private fun readSources(intent: android.content.Intent): List<NativeClient.Source> {
        val ids = intent.getIntArrayExtra("srcIds") ?: return emptyList()
        val w = intent.getIntArrayExtra("srcW") ?: return emptyList()
        val h = intent.getIntArrayExtra("srcH") ?: return emptyList()
        val names = intent.getStringArrayExtra("srcNames") ?: return emptyList()
        if (w.size != ids.size || h.size != ids.size || names.size != ids.size) return emptyList()
        return ids.indices.map { NativeClient.Source(ids[it], w[it], h[it], names[it]) }
    }

    /**
     * Đổi sang màn hình khác của CÙNG host, không rời màn xem.
     *
     * Giao thức không có lệnh "đổi nguồn": mỗi cặp (client, nguồn) là một PHIÊN riêng,
     * nên đổi = đóng phiên cũ rồi mở phiên mới với sourceId khác. Đó đúng là những gì
     * nativeStop + nativeStart làm sẵn.
     *
     * SurfaceView KHÔNG bị dựng lại: JniBridge giữ `g_window` độc lập với vòng đời
     * phiên và nativeStart tự gắn lại nó cho phiên mới (xem nativeStart trong
     * JniBridge.cpp). Nhờ vậy đổi màn hình chỉ tốn một lần bắt tay, không nháy đen do
     * huỷ/tạo lại Surface.
     */
    private fun switchSource(sourceId: Int) {
        if (sourceId == currentSourceId) return
        if (session != 0L) NativeClient.nativeStop(session)
        currentSourceId = sourceId
        session = NativeClient.nativeStart(address, sourceId)
    }

    override fun onStop() {
        super.onStop()
        // Xuống nền là KẾT THÚC phiên. Không có nhánh này thì surfaceDestroyed chỉ
        // thu hồi cửa sổ (frame bị vứt) còn thread Net vẫn nhận trọn bitrate và host
        // vẫn encode/gửi vô hạn — nhiều Mbps và pin đốt cho một app vô hình. Giao
        // thức chưa có "pause" nên dừng hẳn là lựa chọn đúng; bản iOS cũng chết phiên
        // khi xuống nền (host timeout 5s). Xoay màn hình (config change) thì không.
        if (!isFinishing && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        // Chỉ dừng đúng phiên MÌNH tạo — xem chú thích ở `session`.
        if (session != 0L) NativeClient.nativeStop(session)
        super.onDestroy()
    }
}

/**
 * Một phím tắt gửi thẳng sang host — bàn phím ảo không có những phím này.
 * `modVk` != 0 -> tổ hợp (giữ phím bổ trợ rồi gõ phím chính): Ctrl+C, Ctrl+V...
 */
private data class Hotkey(
    val label: String,
    val vk: Int,
    val scan: Int,
    val modVk: Int = 0,
    val modScan: Int = 0,
)

// Thêm phím mới = thêm một dòng: mã phím ảo Windows + scancode US (bit8 = cờ E0
// cho phím mở rộng như mũi tên/Del — xem Wire.h). Không đưa Alt+Tab/phím Win vào:
// chúng chuyển focus khỏi cửa sổ đang chia sẻ, host sẽ ngừng nhận input.
private val kHotkeys =
    listOf(
        Hotkey("Esc", 0x1B, 0x01),
        Hotkey("Tab", 0x09, 0x0F),
        Hotkey("Enter", 0x0D, 0x1C),
        Hotkey("↑", 0x26, 0x148),
        Hotkey("↓", 0x28, 0x150),
        Hotkey("←", 0x25, 0x14B),
        Hotkey("→", 0x27, 0x14D),
        Hotkey("Del", 0x2E, 0x153),
        Hotkey("Ctrl+C", 0x43, 0x2E, modVk = 0x11, modScan = 0x1D),
        Hotkey("Ctrl+V", 0x56, 0x2F, modVk = 0x11, modScan = 0x1D),
    )

@Composable
private fun StreamScreen(
    address: String,
    sessionKey: Long,
    sources: List<NativeClient.Source>,
    currentSourceId: Int,
    holderCallback: SurfaceHolder.Callback,
    onSwitchSource: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val started = sessionKey != 0L
    var phase by remember { mutableIntStateOf(NativeClient.PHASE_IDLE) }
    var statusLine by remember { mutableStateOf("") }
    var endReason by remember { mutableStateOf("") }
    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }

    // Zoom/pan của khung hình. Ở ĐÂY chứ không nằm trong TrackpadOverlay vì cả ô
    // video lẫn trackpad phải nhìn vào CÙNG một khung — xem VideoZoom.kt.
    val transform = remember { VideoTransform() }

    // Hỏi trạng thái từ tầng C++ 500ms/lần. Rẻ hơn nhiều so với để C++ gọi ngược lên
    // JVM mỗi frame, và dòng số liệu chỉ đổi mỗi giây nên không cần nhanh hơn.
    //
    // Khoá theo sessionKey chứ không theo `started`: đổi màn hình sinh một thế hệ MỚI
    // nhưng `started` vẫn true, nên nếu khoá theo nó thì vòng poll cũ chạy tiếp và số
    // liệu/lý-do-kết-thúc của phiên vừa đóng còn dính lại trên màn hình.
    LaunchedEffect(sessionKey) {
        phase = NativeClient.PHASE_IDLE
        statusLine = ""
        endReason = ""
        videoW = 0
        videoH = 0
        // Phiên mới (kể cả đổi màn hình) thì về lại vừa khung: nguồn mới có thể khác
        // tỉ lệ, giữ zoom cũ là mở ra đã thấy một góc lạ hoắc.
        transform.reset()
        transform.setAspect(0f)
        if (!started) return@LaunchedEffect
        while (true) {
            phase = NativeClient.nativePhase()
            statusLine = NativeClient.nativeStatusLine()
            videoW = NativeClient.nativeVideoWidth()
            videoH = NativeClient.nativeVideoHeight()
            transform.setAspect(if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 0f)
            // Hết phiên thì thoát hẳn coroutine: lý do kết thúc không đổi nữa, hỏi
            // tiếp chỉ tốn pin. LaunchedEffect tự hủy coroutine khi rời màn hình.
            if (phase == NativeClient.PHASE_ENDED) {
                endReason = NativeClient.nativeEndReason()
                return@LaunchedEffect
            }
            delay(500)
        }
    }

    val streaming = phase == NativeClient.PHASE_STREAMING

    // Bàn phím ảo: bật/tắt bằng nút Keyboard ở thanh dưới. KeyInputView vô hình giữ
    // focus để IME gửi phím; xem giải thích cơ chế TYPE_NULL trong KeyInputView.kt.
    var keyboardOn by remember { mutableStateOf(false) }
    var keyView by remember { mutableStateOf<KeyInputView?>(null) }
    LaunchedEffect(keyboardOn) {
        val v = keyView ?: return@LaunchedEffect
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!keyboardOn) {
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            return@LaunchedEffect
        }
        v.requestFocus()
        imm.showSoftInput(v, 0)

        // Người dùng có thể hạ bàn phím bằng nút ẩn/Back của chính IME, không qua nút
        // Keyboard — canh ime inset để trạng thái nút không kẹt ở "đang bật". Phải chờ
        // THẤY bàn phím hiện rồi mới canh lúc ẩn, kẻo tắt nhầm khi IME còn đang trượt
        // lên. rootWindowInsets chỉ báo được IME từ API 30; máy cũ hơn giữ hành vi cũ
        // (nút không tự tắt, bấm hai lần để mở lại). Coroutine tự hủy khi keyboardOn
        // đổi hoặc rời màn hình — không rò vòng lặp.
        if (Build.VERSION.SDK_INT < 30) return@LaunchedEffect
        var seen = false
        while (true) {
            val visible =
                v.rootWindowInsets?.isVisible(
                    android.view.WindowInsets.Type
                        .ime(),
                ) == true
            if (visible) {
                seen = true
            } else if (seen) {
                keyboardOn = false
                return@LaunchedEffect
            }
            delay(200)
        }
    }

    // BA TẦNG XẾP DỌC, KHÔNG CHỒNG LÊN NHAU (2026-07-27)
    //   Trước đây thanh trạng thái và thanh nút là HUD nổi ĐÈ lên video. Giờ chúng là
    //   view riêng: video chiếm phần giữa (weight 1f) và không có gì phủ lên nó nữa.
    //   Hệ quả có thật: khung hình nhỏ đi đúng bằng chiều cao hai thanh — đổi lại
    //   không còn chữ hay nút nằm chắn lên nội dung đang xem.
    //
    // Bàn phím ảo vẫn ĐÈ lên (không imePadding/adjustResize) để khung hình đứng yên
    // khi mở bàn phím, chứ không co layout.
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding(),
    ) {
        // --- Thanh trên: địa chỉ + dòng số liệu ---
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = if (videoW > 0) "$address — $videoW×$videoH" else address,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
            )
            if (streaming && statusLine.isNotEmpty()) {
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }

        // --- Giữa: chỉ video (+ trackpad phủ đúng vùng này) ---
        // Đo viewport ĐÚNG MỘT LẦN ở đây rồi cả ô video lẫn trackpad cùng đọc ra từ
        // `transform` — letterbox không còn do Modifier.aspectRatio lo nữa, vì khung
        // video giờ còn phụ thuộc zoom/pan.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { transform.setViewport(it) },
            contentAlignment = Alignment.Center,
        ) {
            if (started) {
                AndroidView(
                    factory = { ctx -> VideoSurfaceHost(ctx, holderCallback) },
                    // Đọc transform.videoRect ở đây: AndroidView chạy lại update khi
                    // state đọc trong nó đổi, nên pinch/pan là chạy thẳng vào layout
                    // của SurfaceView, không dựng lại view nào.
                    update = { host ->
                        val rect = transform.videoRect
                        // Làm tròn theo hai MÉP rồi mới trừ ra bề rộng: làm tròn riêng
                        // left và width thì mép phải nhảy 1px qua lại khi đang pinch.
                        val left = rect.left.roundToInt()
                        val top = rect.top.roundToInt()
                        host.setVideoRect(
                            left,
                            top,
                            rect.right.roundToInt() - left,
                            rect.bottom.roundToInt() - top,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Trackpad phủ trọn ô giữa — gồm cả vùng đen letterbox, nhưng KHÔNG
                // còn chạm tới hai thanh: rê tay lên nút không làm con trỏ nhảy nữa.
                if (streaming) {
                    TrackpadOverlay(
                        transform = transform,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // View hứng phím: 1dp, vô hình, chỉ tồn tại để giữ focus cho IME.
                AndroidView(
                    factory = { ctx ->
                        KeyInputView(ctx)
                            .apply { onChar = { cp -> NativeClient.charTap(cp) } }
                            .also { keyView = it }
                    },
                    modifier = Modifier.size(1.dp),
                )
            }

            // Lớp phủ trạng thái nằm TRONG ô video, không che hai thanh.
            if (!started || phase == NativeClient.PHASE_ENDED) {
                EndedOverlay(
                    reason = if (!started) "Could not connect to $address" else endReason,
                    onBack = onDismiss,
                )
            } else if (!streaming) {
                ConnectingOverlay(address = address)
            }
        }

        // --- Thanh dưới: phím tắt + Display/Keyboard/End ---
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            BottomBar(
                streaming = streaming,
                keyboardOn = keyboardOn,
                sources = sources,
                currentSourceId = currentSourceId,
                transform = transform,
                onToggleKeyboard = { keyboardOn = !keyboardOn },
                onSwitchSource = onSwitchSource,
                onEnd = onDismiss,
            )
        }
    }
}

/** Hộp thoại đổi màn hình — chỉ mở được khi host chia sẻ từ hai nguồn trở lên. */
@Composable
private fun DisplayPickerDialog(
    sources: List<NativeClient.Source>,
    currentSourceId: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display") },
        text = {
            Column {
                sources.forEach { source ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(source.id)
                                    onDismiss()
                                }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = source.id == currentSourceId,
                            onClick = {
                                onPick(source.id)
                                onDismiss()
                            },
                        )
                        Column {
                            Text(source.name.ifBlank { "Source %d".format(source.id) })
                            Text(
                                text = "${source.width}×${source.height}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Thanh dưới: hàng phím tắt cuộn ngang + nút đổi màn hình, bàn phím ảo và Kết thúc. */
@Composable
private fun BottomBar(
    streaming: Boolean,
    keyboardOn: Boolean,
    sources: List<NativeClient.Source>,
    currentSourceId: Int,
    transform: VideoTransform,
    onToggleKeyboard: () -> Unit,
    onSwitchSource: (Int) -> Unit,
    onEnd: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    if (pickerOpen) {
        DisplayPickerDialog(
            sources = sources,
            currentSourceId = currentSourceId,
            onPick = onSwitchSource,
            onDismiss = { pickerOpen = false },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Phím tắt cuộn ngang: hàng này dài hơn bề ngang máy.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            kHotkeys.forEach { hk ->
                OutlinedButton(
                    onClick = {
                        if (hk.modVk != 0) {
                            NativeClient.keyChord(hk.modVk, hk.modScan, hk.vk, hk.scan)
                        } else {
                            NativeClient.keyTap(hk.vk, hk.scan)
                        }
                    },
                    enabled = streaming,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text(hk.label) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onToggleKeyboard, enabled = streaming) {
                Text(if (keyboardOn) "Hide keyboard" else "Keyboard")
            }
            // Chỉ hiện khi có cái để đổi: host một màn hình thì nút này là một câu hỏi
            // không có câu trả lời.
            if (sources.size > 1) {
                OutlinedButton(onClick = { pickerOpen = true }) { Text("Display") }
            }
            // Đường về khi phóng sâu rồi lạc: chỉ hiện đúng lúc đang zoom, và nhãn
            // mang luôn mức zoom hiện tại nên không cần thêm chỗ nào hiển thị nó.
            if (transform.zoomedIn) {
                OutlinedButton(onClick = { transform.reset() }) {
                    Text("Fit %.1f×".format(transform.zoom))
                }
            }
            Box(modifier = Modifier.weight(1f))
            Button(onClick = onEnd) { Text("End") }
        }
    }
}

@Composable
private fun ConnectingOverlay(address: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(text = "Connecting to $address…", color = Color.White)
        }
    }
}

@Composable
private fun EndedOverlay(
    reason: String,
    onBack: () -> Unit,
) {
    // Chạm vào đâu cũng quay lại được — nhưng vẫn có nút thật cho người không đoán ra.
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Session ended",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(text = reason, color = Color.White)
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

/**
 * Trackpad ảo phủ lên khung video, kiểu bàn di chuột laptop: con trỏ LUÔN hiện,
 * ngón tay rê ở đâu cũng được — con trỏ dịch theo DELTA chứ không nhảy tới điểm
 * chạm (ngón tay không che mất chỗ cần bấm, và bấm được chính xác từng pixel).
 *
 *   Rê 1 ngón     = di con trỏ.
 *   Tap 1 lần     = click trái TẠI CON TRỎ.
 *   Tap 2 lần     = click phải tại con trỏ.
 *   Giữ rồi kéo   = giữ chuột trái và rê (kéo cửa sổ, bôi đen), nhấc tay là nhả.
 *   2 ngón        = pinch phóng to / rê khung nhìn. Không gửi gì sang host; con trỏ
 *                   bị mép khung đẩy theo nếu nó trôi ra ngoài vùng đang nhìn.
 *
 * CON TRỎ LƯU Ở KHÔNG GIAN NỘI DUNG, KHÔNG PHẢI PIXEL MÀN HÌNH
 *   `cursor` là toạ độ 0..1 trên MÀN HÌNH PC; chỗ vẽ nó là suy ra
 *   (transform.contentToScreen) và toạ độ gửi đi chỉ là cursor × 65535. Hai cái lợi,
 *   đều là bắt buộc khi có zoom:
 *     - Đổi zoom/pan KHÔNG làm chuột bên PC nhúc nhích: nội dung có đổi đâu.
 *     - Delta ngón tay chia cho bề rộng khung ĐÃ PHÓNG, nên phóng càng sâu con trỏ
 *       đi càng chậm — đúng chế độ ngắm chính xác mà người ta zoom để có.
 *
 * Overlay phủ CẢ vùng hiển thị (gồm vùng đen letterbox); con trỏ bị kẹp trong
 * 0..1 nên không bao giờ ra ngoài khung video thật.
 */
@Composable
private fun TrackpadOverlay(
    transform: VideoTransform,
    modifier: Modifier,
) {
    var cursor by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    // Chốt "cử chỉ đang diễn ra đã từng có ≥2 ngón" — xem pointerInput đầu tiên.
    var multiTouch by remember { mutableStateOf(false) }
    // Long-press drag có thật sự nhấn chuột xuống không: pinch chen ngang thì bỏ qua
    // onDragStart, và khi đó onDragEnd cũng không được nhả một nút chưa hề nhấn.
    var holdingLeft by remember { mutableStateOf(false) }
    // Chừa mép để con trỏ không dính sát biên lúc auto-pan (xem ensureVisible).
    val autoPanMarginPx = with(LocalDensity.current) { 40.dp.toPx() }

    fun send() {
        NativeClient.mouseMove(
            (cursor.x * 65535f).roundToInt(),
            (cursor.y * 65535f).roundToInt(),
        )
    }

    fun moveBy(delta: Offset) {
        val rect = transform.videoRect
        if (rect.width <= 0f || rect.height <= 0f) return
        cursor =
            Offset(
                (cursor.x + delta.x / rect.width).coerceIn(0f, 1f),
                (cursor.y + delta.y / rect.height).coerceIn(0f, 1f),
            )
        // Đang phóng to thì con trỏ dễ chạy ra ngoài phần đang nhìn thấy — kéo khung
        // theo nó. Ở mức zoom = 1 pan luôn bị kẹp về 0 nên hàm này không làm gì.
        transform.ensureVisible(cursor, autoPanMarginPx)
        send()
    }

    // Host cũng có người dùng thật di chuột được — gửi lại vị trí con trỏ ngay
    // trước mỗi cú click để chắc chắn click rơi đúng chỗ con trỏ đang hiển thị.
    fun clickAt(button: Int) {
        send()
        NativeClient.mouseButton(button, true)
        NativeClient.mouseButton(button, false)
    }

    Box(
        modifier =
            modifier
                .pointerInput(Unit) {
                    // Đếm ngón ở pass Initial (cha -> con): thấy MỌI sự kiện trước
                    // khi bất kỳ nhánh nào tiêu thụ, nên các nhánh 1 ngón bên dưới
                    // biết chắc chắn có đang pinch hay không.
                    //
                    // Chốt chỉ hạ khi một cử chỉ MỚI bắt đầu (0 ngón -> có ngón), chứ
                    // không hạ lúc nhấc tay: cú nhấc tay kết thúc pinch mà bị coi là
                    // gesture sạch thì detectTapGestures sẽ bắn ra một cú click.
                    awaitPointerEventScope {
                        var prevPressed = 0
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (prevPressed == 0 && pressed > 0) multiTouch = false
                            if (pressed >= 2) multiTouch = true
                            prevPressed = pressed
                        }
                    }
                }.pointerInput(Unit) {
                    // Có onDoubleTap nên onTap phải chờ hết cửa sổ double-tap
                    // (~300ms) mới nổ — giá phải trả để phân biệt được hai cử chỉ.
                    detectTapGestures(
                        onTap = { if (!multiTouch) clickAt(NativeClient.MOUSE_LEFT) },
                        onDoubleTap = { if (!multiTouch) clickAt(NativeClient.MOUSE_RIGHT) },
                    )
                }.pointerInput(Unit) {
                    // Rê tự do (không giữ nút nào): di con trỏ theo delta.
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            if (!multiTouch) moveBy(delta)
                        },
                    )
                }.pointerInput(Unit) {
                    // Giữ yên tới ngưỡng long-press RỒI kéo = drag giữ chuột trái.
                    // Không tranh chấp với detectDrag thường: bên đó cần vượt touch
                    // slop trước, bên này cần đứng yên trước — loại trừ lẫn nhau.
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            if (!multiTouch) {
                                send()
                                NativeClient.mouseButton(NativeClient.MOUSE_LEFT, true)
                                holdingLeft = true
                            }
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            if (holdingLeft) moveBy(delta)
                        },
                        onDragEnd = {
                            if (holdingLeft) {
                                NativeClient.mouseButton(NativeClient.MOUSE_LEFT, false)
                                holdingLeft = false
                            }
                        },
                        onDragCancel = {
                            if (holdingLeft) {
                                NativeClient.mouseButton(NativeClient.MOUSE_LEFT, false)
                                holdingLeft = false
                            }
                        },
                    )
                }.pointerInput(Unit) {
                    // HAI NGÓN: pinch = zoom, rê = dời khung nhìn. Tự viết vòng lặp
                    // chứ không dùng detectTransformGestures vì hàm đó tiêu thụ cả
                    // chuyển động MỘT ngón sau khi vượt touch slop — dùng nó là mất
                    // luôn đường di con trỏ. Ở đây chỉ tiêu thụ khi thật sự có ≥2 ngón.
                    //
                    // Đặt CUỐI chuỗi modifier: pass Main đi từ con ra cha, nên nhánh
                    // này được xử lý trước và các nhánh 1 ngón ở trên thấy sự kiện đã
                    // bị tiêu thụ mà tự huỷ.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var prevCentroid = Offset.Unspecified
                        var prevSpread = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val points = event.changes.filter { it.pressed }
                            if (points.size < 2) {
                                // Nhấc bớt còn 1 ngón: quên mốc cũ, đừng để cú rê tiếp
                                // theo bị tính như một bước pinch khổng lồ.
                                prevCentroid = Offset.Unspecified
                                prevSpread = 0f
                                if (points.isEmpty()) break
                                continue
                            }
                            val centroid =
                                points.fold(Offset.Zero) { acc, p -> acc + p.position } /
                                    points.size.toFloat()
                            val spread =
                                points.fold(0f) { acc, p -> acc + (p.position - centroid).getDistance() } /
                                    points.size
                            if (prevCentroid.isSpecified) {
                                if (prevSpread > 0f && spread > 0f) {
                                    transform.zoomBy(spread / prevSpread, centroid)
                                }
                                transform.panBy(centroid - prevCentroid)
                                // Khung là ý muốn của người dùng, con trỏ mới là thứ
                                // phải nhường: để nó bị mép khung đẩy đi thay vì kéo
                                // khung về theo nó. KHÔNG gửi gì — chuột bên PC chỉ
                                // nhúc nhích khi người dùng thật sự rê/bấm, và lúc đó
                                // toạ độ tuyệt đối được gửi kèm nên hai bên không lệch.
                                cursor = transform.clampToVisible(cursor, autoPanMarginPx)
                            }
                            prevCentroid = centroid
                            prevSpread = spread
                            points.forEach { it.consume() }
                        }
                    }
                },
    ) {
        // Chưa đo được ô video thì chưa biết vẽ con trỏ ở đâu — vẽ sớm là nó nháy
        // một frame ở góc trên-trái.
        if (transform.viewport.width > 0) {
            CursorArrow(
                modifier =
                    Modifier.offset {
                        val p = transform.contentToScreen(cursor)
                        IntOffset(p.x.roundToInt(), p.y.roundToInt())
                    },
            )
        }
    }
}

/** Mũi tên con trỏ — vẽ tay bằng Path, trắng viền đen để nổi trên mọi nền video. */
@Composable
private fun CursorArrow(modifier: Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val p =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(0f, h * 0.80f)
                lineTo(w * 0.22f, h * 0.62f)
                lineTo(w * 0.40f, h * 0.98f)
                lineTo(w * 0.55f, h * 0.90f)
                lineTo(w * 0.37f, h * 0.55f)
                lineTo(w * 0.63f, h * 0.55f)
                close()
            }
        drawPath(p, Color.White)
        drawPath(p, Color.Black, style = Stroke(width = 1.dp.toPx()))
    }
}
