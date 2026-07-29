// =============================================================================
// VideoZoom.kt — PHÓNG TO khung hình phía CLIENT: trạng thái zoom/pan + khung chứa
//                SurfaceView để phóng được mà không tràn ra ngoài ô video.
//
// VÌ SAO CÓ TÍNH NĂNG NÀY
//   Host LUÔN gửi màn hình ở độ phân giải GỐC: AgentLoop dựng offer thẳng từ kích
//   thước nguồn và bỏ qua maxWidth/maxHeight mà HELLO khai. Điện thoại vì thế nhận
//   đủ số pixel của màn hình PC rồi thu nhỏ lại cho vừa ô video — chữ trên màn 27"
//   nhìn qua máy 6" thì không đọc nổi. Zoom ở đây chỉ là THÔI thu nhỏ: phóng đúng
//   khung đã giải mã và cho phép rê quanh.
//
// VÌ SAO PHÓNG Ở CLIENT CHỨ KHÔNG BẢO HOST CẮT VÙNG NHÌN
//   Cắt phía host (gửi riêng vùng đang xem, encode ở đúng cỡ màn hình điện thoại)
//   cho chi tiết vô hạn và tốn ít băng thông hơn, nhưng phải: thêm một MsgType vào
//   Wire.h + docs/04-protocol.md, chèn bước crop trước NVENC (NVENC nhận thẳng
//   texture WGC, không có crop), và dựng lại encoder + ép IDR mỗi lần đổi mức zoom —
//   một trận bão IDR đúng vào thứ BitrateController đang cố tránh. Phóng ở client
//   không đụng một byte nào của giao thức và KHÔNG tốn thêm tài nguyên: buffer của
//   Surface vẫn đúng bằng độ phân giải video (do codec quyết định), kích thước view
//   chỉ là hình chữ nhật ĐÍCH mà hardware composer scale tới.
//
// HAI HỆ TOẠ ĐỘ, ĐỪNG LẪN
//   "nội dung" — 0..1 trên màn hình PC. Con trỏ trackpad sống ở đây, và toạ độ gửi
//                đi chỉ là nội-dung × 65535.
//   "màn hình" — pixel trong ô video của điện thoại. Chỉ để vẽ và để nhận cử chỉ.
//   videoRect = fitRect (aspect-fit canh giữa) phóng `zoom` lần quanh TÂM viewport
//   rồi dời `pan`. Mọi thứ khác suy ra từ nó.
//
// LIÊN QUAN: StreamActivity.kt (TrackpadOverlay + ô video dùng chung một VideoTransform)
// =============================================================================
package com.deskhub.app

import android.content.Context
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize

// Nhỏ nhất = vừa khung; quá 5× thì không còn pixel thật nào để moi ra nữa, chỉ là
// nội suy — để trần ở đó cho khỏi lạc.
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/**
 * Trạng thái zoom/pan của ô video, dùng CHUNG cho cả khung hình lẫn trackpad.
 *
 * Phải chung một đối tượng: trackpad chuẩn hoá toạ độ theo đúng cái khung mà người
 * dùng đang nhìn, hai bên tự tính riêng là lệch nhau ngay khi có zoom.
 *
 * `zoom`/`pan` chỉ đổi qua các hàm ở đây vì lần nào cũng phải kẹp lại (clampPan):
 * mép video không bao giờ được phép thụt vào trong viewport, còn khi ảnh nhỏ hơn
 * viewport thì nó bị ghim đúng giữa — y như letterbox lúc chưa có zoom.
 */
@Stable
class VideoTransform {
    var viewport by mutableStateOf(IntSize.Zero)
        private set

    /** Tỉ lệ khung video (rộng/cao); 0 = chưa biết -> lấp đầy viewport. */
    var aspect by mutableFloatStateOf(0f)
        private set

    var zoom by mutableFloatStateOf(MIN_ZOOM)
        private set

    var pan by mutableStateOf(Offset.Zero)
        private set

    /** Có đang phóng to không — dùng để hiện nút "Fit". */
    val zoomedIn: Boolean get() = zoom > MIN_ZOOM * 1.005f

    /** Khung video khi zoom = 1: aspect-fit, canh giữa viewport. */
    val fitRect: Rect
        get() {
            val vw = viewport.width.toFloat()
            val vh = viewport.height.toFloat()
            if (vw <= 0f || vh <= 0f) return Rect.Zero
            if (aspect <= 0f) return Rect(0f, 0f, vw, vh)
            return if (vw / vh > aspect) {
                val w = vh * aspect // thừa ngang: video cao hết cỡ, đen hai bên
                Rect((vw - w) / 2f, 0f, (vw + w) / 2f, vh)
            } else {
                val h = vw / aspect // thừa dọc: video rộng hết cỡ, đen trên dưới
                Rect(0f, (vh - h) / 2f, vw, (vh + h) / 2f)
            }
        }

    /** Khung video THẬT sau zoom + pan. Zoom > 1 thì nó TRÀN ra ngoài viewport — đó
     *  là bình thường, phần thừa do VideoSurfaceHost cắt. */
    val videoRect: Rect
        get() {
            val fit = fitRect
            if (fit.width <= 0f || fit.height <= 0f) return Rect.Zero
            val w = fit.width * zoom
            val h = fit.height * zoom
            val cx = viewport.width / 2f + pan.x
            val cy = viewport.height / 2f + pan.y
            return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }

    fun setViewport(size: IntSize) {
        if (size == viewport) return
        viewport = size
        pan = clampPan(pan) // xoay máy: khung mới hẹp hơn thì pan cũ có thể hết hợp lệ
    }

    fun setAspect(value: Float) {
        if (value == aspect) return
        aspect = value
        pan = clampPan(pan)
    }

    /** Về lại vừa khung. Nút "Fit" và mỗi phiên mới đều gọi cái này. */
    fun reset() {
        zoom = MIN_ZOOM
        pan = Offset.Zero
    }

    fun contentToScreen(content: Offset): Offset {
        val r = videoRect
        return Offset(r.left + content.x * r.width, r.top + content.y * r.height)
    }

    fun screenToContent(point: Offset): Offset {
        val r = videoRect
        if (r.width <= 0f || r.height <= 0f) return Offset(0.5f, 0.5f)
        return Offset((point.x - r.left) / r.width, (point.y - r.top) / r.height)
    }

    fun panBy(delta: Offset) {
        if (delta == Offset.Zero) return
        pan = clampPan(pan + delta)
    }

    /**
     * Nhân mức zoom lên `factor`, giữ nguyên điểm nội dung đang nằm dưới `focus`
     * (tâm hai ngón tay) — pinch mà điểm dưới ngón chạy đi là mất phương hướng ngay.
     */
    fun zoomBy(
        factor: Float,
        focus: Offset,
    ) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        val content = screenToContent(focus)
        val fit = fitRect
        zoom = next
        // Giải ngược điều kiện contentToScreen(content) == focus ra pan:
        //   focus = viewport/2 + pan - fit*zoom/2 + content*fit*zoom
        pan =
            clampPan(
                Offset(
                    focus.x - viewport.width / 2f + fit.width * next * (0.5f - content.x),
                    focus.y - viewport.height / 2f + fit.height * next * (0.5f - content.y),
                ),
            )
    }

    /**
     * Kéo khung nhìn theo cho điểm nội dung `content` (con trỏ) không nằm sát mép.
     * Không có bước này thì zoom sâu xong sẽ có những vùng màn hình PC không tài nào
     * với tới được: con trỏ đi ra khỏi phần đang nhìn thấy và biến mất.
     */
    fun ensureVisible(
        content: Offset,
        margin: Float,
    ) {
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val m = margin.coerceAtMost(minOf(vw, vh) / 3f)
        val p = contentToScreen(content)
        val dx =
            when {
                p.x < m -> m - p.x
                p.x > vw - m -> vw - m - p.x
                else -> 0f
            }
        val dy =
            when {
                p.y < m -> m - p.y
                p.y > vh - m -> vh - m - p.y
                else -> 0f
            }
        panBy(Offset(dx, dy))
    }

    /**
     * Kẹp một điểm nội dung vào phần đang NHÌN THẤY và trả về vị trí mới.
     *
     * Đối xứng của [ensureVisible]: khi NGƯỜI DÙNG tự rê khung nhìn thì khung là ý
     * muốn, không được kéo nó về theo con trỏ — thay vào đó con trỏ bị mép khung đẩy
     * đi. Không có bước này thì rê sang vùng khác rồi chạm một ngón là khung nhảy
     * ngược về chỗ con trỏ cũ, mà đó gần như luôn là chỗ người dùng vừa rời đi.
     */
    fun clampToVisible(
        content: Offset,
        margin: Float,
    ): Offset {
        val r = videoRect
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        if (r.width <= 0f || r.height <= 0f || vw <= 0f || vh <= 0f) return content
        val m = margin.coerceAtMost(minOf(vw, vh) / 3f)
        // Giao của "trong viewport (trừ hao mép)" và "trong khung video" — giao rỗng
        // (khung quá nhỏ) thì để yên, kẹp bừa chỉ làm con trỏ nhảy vô cớ.
        val loX = maxOf(r.left, m)
        val hiX = minOf(r.right, vw - m)
        val loY = maxOf(r.top, m)
        val hiY = minOf(r.bottom, vh - m)
        val p = contentToScreen(content)
        val clamped =
            Offset(
                if (loX > hiX) p.x else p.x.coerceIn(loX, hiX),
                if (loY > hiY) p.y else p.y.coerceIn(loY, hiY),
            )
        val back = screenToContent(clamped)
        return Offset(back.x.coerceIn(0f, 1f), back.y.coerceIn(0f, 1f))
    }

    // Mép video không được thụt vào trong viewport; ảnh nhỏ hơn viewport thì ghim
    // đúng giữa (biên = 0). Đối xứng vì videoRect phóng quanh TÂM viewport.
    private fun clampPan(value: Offset): Offset {
        val fit = fitRect
        val maxX = ((fit.width * zoom - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fit.height * zoom - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
}

/**
 * Khung chứa SurfaceView, đặt nó đúng vào videoRect và CẮT phần tràn ra ngoài.
 *
 * Vì sao phải là một ViewGroup thật chứ không phải Modifier.clipToBounds của Compose:
 * SurfaceView không được Compose vẽ. Nó là một lỗ thủng trên cửa sổ, do hệ thống View
 * dựng ra và do SurfaceFlinger ghép hình vào; clip của Compose chỉ áp cho phần Compose
 * tự vẽ, còn View con thì nằm trong AndroidViewsHandler (clipChildren = false). Không
 * có lớp này, khung đang phóng to sẽ tràn lên thanh trạng thái và thanh nút.
 *
 * Đặt bằng LAYOUT THẬT (width/height + margin) chứ không phải scaleX/translationX:
 * vị trí surface được suy từ vị trí layout của view, nên đi đường layout là đường mà
 * SurfaceView bảo đảm xử lý đúng.
 */
class VideoSurfaceHost(
    context: Context,
    callback: SurfaceHolder.Callback,
) : FrameLayout(context) {
    private val surfaceView = SurfaceView(context)

    // Khung đã đặt lần trước. Tên có tiền tố `rect` chứ không phải left/top/width/
    // height: những cái tên đó là thuộc tính SẴN CÓ của View, trùng vào là vừa che
    // mất bản gốc vừa đụng chữ ký getLeft()/getWidth() phía JVM.
    private var rectLeft = 0
    private var rectTop = 0
    private var rectWidth = 0
    private var rectHeight = 0

    init {
        clipChildren = true
        surfaceView.holder.addCallback(callback)
        // Trước lần setVideoRect đầu tiên (chưa đo được viewport) thì lấp đầy khung —
        // đúng hành vi hồi chưa có zoom, không nháy.
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        addView(surfaceView, LayoutParams(fill, fill, Gravity.TOP or Gravity.START))
    }

    /**
     * Đặt khung video theo pixel, hệ toạ độ của chính view này. `l`/`t` ÂM và
     * `w`/`h` lớn hơn khung là chuyện bình thường khi đang zoom.
     */
    fun setVideoRect(
        l: Int,
        t: Int,
        w: Int,
        h: Int,
    ) {
        if (w <= 0 || h <= 0) return // chưa đo xong: giữ nguyên MATCH_PARENT
        if (l == rectLeft && t == rectTop && w == rectWidth && h == rectHeight) return
        rectLeft = l
        rectTop = t
        rectWidth = w
        rectHeight = h
        val lp = surfaceView.layoutParams as LayoutParams
        lp.width = w
        lp.height = h
        lp.leftMargin = l
        lp.topMargin = t
        surfaceView.layoutParams = lp // kéo theo requestLayout
    }
}
