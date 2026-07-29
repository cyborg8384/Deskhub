// =============================================================================
// VideoZoom.swift — PHÓNG TO khung hình phía client (zoom + pan).
//                   Đối ứng VideoZoom.kt bên Android, cùng công thức từng dòng.
//
// VÌ SAO CÓ TÍNH NĂNG NÀY
//   Host LUÔN gửi màn hình ở độ phân giải GỐC: AgentLoop dựng offer thẳng từ kích
//   thước nguồn và bỏ qua maxWidth/maxHeight mà HELLO khai. Điện thoại vì thế nhận
//   đủ số pixel của màn hình PC rồi thu nhỏ lại cho vừa ô video — chữ trên màn 27"
//   nhìn qua máy 6" thì không đọc nổi. Zoom ở đây chỉ là THÔI thu nhỏ.
//
// VÌ SAO PHÓNG Ở CLIENT CHỨ KHÔNG BẢO HOST CẮT VÙNG NHÌN
//   Cắt phía host cho chi tiết vô hạn và tốn ít băng thông hơn, nhưng phải thêm một
//   MsgType vào Wire.h + docs/04-protocol.md, chèn bước crop trước NVENC, và dựng
//   lại encoder + ép IDR mỗi lần đổi mức zoom. Phóng ở client không đụng một byte
//   nào của giao thức và không tốn thêm tài nguyên: CMSampleBuffer vẫn giữ nguyên
//   độ phân giải video, chỉ có transform của layer đổi — đúng việc mà bộ ghép hình
//   vẫn làm sẵn cho mọi layer.
//
// KHÁC ANDROID ĐÚNG MỘT CHỖ: KHÔNG CẦN LỚP CẮT RIÊNG
//   Bên kia phải dựng VideoSurfaceHost vì SurfaceView là lỗ thủng trên cửa sổ, không
//   ai clip hộ. Ở đây khung hình sống trong AVSampleBufferDisplayLayer — một CALayer
//   bình thường — nên .clipped() của SwiftUI là đủ, và zoom/pan chỉ là scaleEffect +
//   offset trên chính view đó (xem StreamView.videoArea).
//
// HAI HỆ TOẠ ĐỘ, ĐỪNG LẪN
//   "nội dung" — 0..1 trên màn hình PC. Con trỏ trackpad sống ở đây, và toạ độ gửi
//                đi chỉ là nội-dung × 65535.
//   "màn hình" — point trong ô video của điện thoại. Chỉ để vẽ và để nhận cử chỉ.
//   videoRect = fitRect (aspect-fit canh giữa) phóng `zoom` lần quanh TÂM viewport
//   rồi dời `pan` — đúng thứ mà .scaleEffect(zoom).offset(pan) dựng ra.
//
// LIÊN QUAN: StreamView.swift (đặt khung + đo viewport), TouchInputView.swift (cử chỉ)
// =============================================================================
import CoreGraphics
import Observation

// Nhỏ nhất = vừa khung; quá 5× thì không còn pixel thật nào để moi ra nữa, chỉ là
// nội suy — để trần ở đó cho khỏi lạc.
private let kMinZoom: CGFloat = 1
private let kMaxZoom: CGFloat = 5

private func clamp(_ value: CGFloat, _ lower: CGFloat, _ upper: CGFloat) -> CGFloat {
    min(max(value, lower), upper)
}

/// Trạng thái zoom/pan của ô video, dùng CHUNG cho cả khung hình lẫn trackpad.
///
/// Phải chung một đối tượng: trackpad chuẩn hoá toạ độ theo đúng cái khung mà người
/// dùng đang nhìn, hai bên tự tính riêng là lệch nhau ngay khi có zoom.
///
/// `zoom`/`pan` chỉ đổi qua các hàm ở đây vì lần nào cũng phải kẹp lại (clampPan):
/// mép video không bao giờ được phép thụt vào trong viewport, còn khi ảnh nhỏ hơn
/// viewport thì nó bị ghim đúng giữa — y như letterbox lúc chưa có zoom.
@MainActor @Observable
final class VideoTransform {
    private(set) var viewport: CGSize = .zero

    /// Tỉ lệ khung video (rộng/cao). StreamView truyền đúng giá trị nó dùng cho
    /// .aspectRatio, nên fitRect ở đây trùng khít khung SwiftUI vẽ ra.
    private(set) var aspect: CGFloat = 0

    private(set) var zoom: CGFloat = kMinZoom

    private(set) var pan: CGPoint = .zero

    /// Có đang phóng to không — dùng để hiện nút "Fit".
    var zoomedIn: Bool { zoom > kMinZoom * 1.005 }

    /// Khung video khi zoom = 1: aspect-fit, canh giữa viewport.
    var fitRect: CGRect {
        let vwidth = viewport.width
        let vheight = viewport.height
        guard vwidth > 0, vheight > 0 else { return .zero }
        guard aspect > 0 else { return CGRect(x: 0, y: 0, width: vwidth, height: vheight) }
        if vwidth / vheight > aspect {
            let width = vheight * aspect // thừa ngang: video cao hết cỡ, đen hai bên
            return CGRect(x: (vwidth - width) / 2, y: 0, width: width, height: vheight)
        }
        let height = vwidth / aspect // thừa dọc: video rộng hết cỡ, đen trên dưới
        return CGRect(x: 0, y: (vheight - height) / 2, width: vwidth, height: height)
    }

    /// Khung video THẬT sau zoom + pan. Zoom > 1 thì nó TRÀN ra ngoài viewport — đó
    /// là bình thường, phần thừa do .clipped() bên StreamView cắt.
    var videoRect: CGRect {
        let fit = fitRect
        guard fit.width > 0, fit.height > 0 else { return .zero }
        let width = fit.width * zoom
        let height = fit.height * zoom
        let centerX = viewport.width / 2 + pan.x
        let centerY = viewport.height / 2 + pan.y
        return CGRect(x: centerX - width / 2, y: centerY - height / 2, width: width, height: height)
    }

    func setViewport(_ size: CGSize) {
        guard size != viewport else { return }
        viewport = size
        pan = clampPan(pan) // xoay máy: khung mới hẹp hơn thì pan cũ có thể hết hợp lệ
    }

    func setAspect(_ value: CGFloat) {
        guard value != aspect else { return }
        aspect = value
        pan = clampPan(pan)
    }

    /// Về lại vừa khung. Nút "Fit" và mỗi phiên mới đều gọi cái này.
    func reset() {
        zoom = kMinZoom
        pan = .zero
    }

    func contentToScreen(_ content: CGPoint) -> CGPoint {
        let rect = videoRect
        return CGPoint(x: rect.minX + content.x * rect.width, y: rect.minY + content.y * rect.height)
    }

    func screenToContent(_ point: CGPoint) -> CGPoint {
        let rect = videoRect
        guard rect.width > 0, rect.height > 0 else { return CGPoint(x: 0.5, y: 0.5) }
        return CGPoint(x: (point.x - rect.minX) / rect.width, y: (point.y - rect.minY) / rect.height)
    }

    func panBy(_ delta: CGPoint) {
        guard delta != .zero else { return }
        pan = clampPan(CGPoint(x: pan.x + delta.x, y: pan.y + delta.y))
    }

    /// Nhân mức zoom lên `factor`, giữ nguyên điểm nội dung đang nằm dưới `focus`
    /// (tâm hai ngón tay) — pinch mà điểm dưới ngón chạy đi là mất phương hướng ngay.
    func zoomBy(_ factor: CGFloat, focus: CGPoint) {
        let next = clamp(zoom * factor, kMinZoom, kMaxZoom)
        guard next != zoom else { return }
        let content = screenToContent(focus)
        let fit = fitRect
        zoom = next
        // Giải ngược điều kiện contentToScreen(content) == focus ra pan:
        //   focus = viewport/2 + pan - fit*zoom/2 + content*fit*zoom
        pan = clampPan(CGPoint(
            x: focus.x - viewport.width / 2 + fit.width * next * (0.5 - content.x),
            y: focus.y - viewport.height / 2 + fit.height * next * (0.5 - content.y)
        ))
    }

    /// Kéo khung nhìn theo cho điểm nội dung `content` (con trỏ) không nằm sát mép.
    /// Không có bước này thì zoom sâu xong sẽ có những vùng màn hình PC không tài nào
    /// với tới được: con trỏ đi ra khỏi phần đang nhìn thấy và biến mất.
    func ensureVisible(_ content: CGPoint, margin: CGFloat) {
        let vwidth = viewport.width
        let vheight = viewport.height
        guard vwidth > 0, vheight > 0 else { return }
        let inset = min(margin, min(vwidth, vheight) / 3)
        let point = contentToScreen(content)
        var dx: CGFloat = 0
        var dy: CGFloat = 0
        if point.x < inset { dx = inset - point.x } else if point.x > vwidth - inset { dx = vwidth - inset - point.x }
        if point.y < inset { dy = inset - point.y } else if point.y > vheight - inset { dy = vheight - inset - point.y }
        panBy(CGPoint(x: dx, y: dy))
    }

    /// Kẹp một điểm nội dung vào phần đang NHÌN THẤY và trả về vị trí mới.
    ///
    /// Đối xứng của `ensureVisible`: khi NGƯỜI DÙNG tự rê khung nhìn thì khung là ý
    /// muốn, không được kéo nó về theo con trỏ — thay vào đó con trỏ bị mép khung đẩy
    /// đi. Không có bước này thì rê sang vùng khác rồi chạm một ngón là khung nhảy
    /// ngược về chỗ con trỏ cũ, mà đó gần như luôn là chỗ người dùng vừa rời đi.
    func clampToVisible(_ content: CGPoint, margin: CGFloat) -> CGPoint {
        let rect = videoRect
        let vwidth = viewport.width
        let vheight = viewport.height
        guard rect.width > 0, rect.height > 0, vwidth > 0, vheight > 0 else { return content }
        let inset = min(margin, min(vwidth, vheight) / 3)
        // Giao của "trong viewport (trừ hao mép)" và "trong khung video" — giao rỗng
        // (khung quá nhỏ) thì để yên, kẹp bừa chỉ làm con trỏ nhảy vô cớ.
        let lowX = max(rect.minX, inset)
        let highX = min(rect.maxX, vwidth - inset)
        let lowY = max(rect.minY, inset)
        let highY = min(rect.maxY, vheight - inset)
        let point = contentToScreen(content)
        let clamped = CGPoint(
            x: lowX > highX ? point.x : clamp(point.x, lowX, highX),
            y: lowY > highY ? point.y : clamp(point.y, lowY, highY)
        )
        let back = screenToContent(clamped)
        return CGPoint(x: clamp(back.x, 0, 1), y: clamp(back.y, 0, 1))
    }

    // Mép video không được thụt vào trong viewport; ảnh nhỏ hơn viewport thì ghim
    // đúng giữa (biên = 0). Đối xứng vì videoRect phóng quanh TÂM viewport.
    private func clampPan(_ value: CGPoint) -> CGPoint {
        let fit = fitRect
        let maxX = max((fit.width * zoom - viewport.width) / 2, 0)
        let maxY = max((fit.height * zoom - viewport.height) / 2, 0)
        return CGPoint(x: clamp(value.x, -maxX, maxX), y: clamp(value.y, -maxY, maxY))
    }
}
