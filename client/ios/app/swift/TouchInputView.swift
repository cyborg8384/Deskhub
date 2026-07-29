// =============================================================================
// TouchInputView.swift — trackpad ảo phủ lên vùng hiển thị, kiểu bàn di chuột laptop.
//                        Đối ứng TrackpadOverlay bên Android.
//
// VÌ SAO TRACKPAD CHỨ KHÔNG PHẢI CHẠM-TRỰC-TIẾP
//   Chạm thẳng vào điểm muốn click nghe hợp lý nhưng khó dùng thật: ngón tay che
//   mất chỗ cần bấm và không bấm chính xác được mục tiêu nhỏ. Trackpad tách ngón
//   tay khỏi con trỏ: con trỏ LUÔN hiện, ngón rê ở đâu cũng được — kể cả vùng đen
//   letterbox quanh video (overlay phủ CẢ vùng hiển thị, không chỉ khung video).
//
// CỬ CHỈ -> CHUỘT
//   Rê 1 ngón     = di con trỏ.
//   Tap 1 lần     = click trái TẠI CON TRỎ (chờ hết cửa sổ double-tap mới nổ).
//   Tap 2 lần     = click phải tại con trỏ.
//   Giữ rồi kéo   = giữ chuột trái và rê (kéo cửa sổ, bôi đen), nhấc tay là nhả.
//   2 ngón        = pinch phóng to / rê khung nhìn. Không gửi gì sang host; con trỏ
//                   bị mép khung đẩy theo nếu nó trôi ra ngoài vùng đang nhìn.
//   Các recognizer một ngón loại trừ nhau sẵn: pan cần chuyển động trước, long
//   press cần đứng yên trước. Riêng cặp cử chỉ khung nhìn được phép chen ngang, và
//   `isTransforming` là thứ giữ cho con trỏ đứng yên trong lúc đó.
//
// TOẠ ĐỘ (đổi 2026-07-29 cùng với zoom)
//   `cursor` lưu ở KHÔNG GIAN NỘI DUNG — điểm 0..1 trên màn hình PC — chứ không phải
//   point trên máy. Chỗ vẽ nó là suy ra (VideoTransform.contentToScreen) và toạ độ
//   gửi đi chỉ là cursor × 65535, đúng hệ mà InputInjector bên host mong đợi. Hai
//   cái lợi, đều là bắt buộc khi có zoom:
//     - Đổi zoom/pan KHÔNG làm chuột bên PC nhúc nhích: nội dung có đổi đâu.
//     - Delta ngón tay chia cho bề rộng khung ĐÃ PHÓNG, nên phóng càng sâu con trỏ
//       đi càng chậm — đúng chế độ ngắm chính xác mà người ta zoom để có.
//
// LIÊN QUAN: StreamView.swift (nơi đặt overlay), VideoZoom.swift (phép toán khung),
//            SessionModel (chuyển tiếp xuống C++)
// =============================================================================
import SwiftUI
import UIKit

struct TouchInputView: UIViewRepresentable {
    let model: SessionModel
    let transform: VideoTransform

    func makeUIView(context _: Context) -> TouchCaptureUIView {
        let view = TouchCaptureUIView()
        view.model = model
        view.transform = transform
        return view
    }

    func updateUIView(_ uiView: TouchCaptureUIView, context _: Context) {
        uiView.model = model
        uiView.transform = transform
        uiView.refreshCursor()
    }
}

final class TouchCaptureUIView: UIView {
    weak var model: SessionModel?
    weak var transform: VideoTransform?

    // Mũi tên con trỏ: SF Symbol trắng + bóng đen để nổi trên mọi nền video.
    private let cursorView: UIImageView = {
        let view = UIImageView(image: UIImage(systemName: "cursorarrow"))
        view.tintColor = .white
        view.layer.shadowColor = UIColor.black.cgColor
        view.layer.shadowOpacity = 0.9
        view.layer.shadowOffset = .zero
        view.layer.shadowRadius = 1.5
        view.frame = CGRect(x: 0, y: 0, width: 18, height: 20)
        return view
    }()

    // Điểm 0..1 trên màn hình PC. Bắt đầu ở giữa và KHÔNG gửi gì — đừng tự di chuột
    // host khi vừa kết nối.
    private var cursor = CGPoint(x: 0.5, y: 0.5)
    private var lastDragLocation: CGPoint = .zero
    // Long press có thật sự nhấn chuột xuống không: pinch chen ngang thì bỏ qua, và
    // khi đó lúc nhấc tay cũng không được nhả một nút chưa hề nhấn.
    private var holdingLeft = false
    // Chừa mép để con trỏ không dính sát biên lúc khung tự chạy theo.
    private let autoPanMargin: CGFloat = 40

    private let pinch = UIPinchGestureRecognizer()
    private let viewportPan = UIPanGestureRecognizer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        // Bật đa chạm: cả pinch lẫn pan hai ngón đều cần, và bản trước tắt nó.
        isMultipleTouchEnabled = true
        addSubview(cursorView)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap))
        doubleTap.numberOfTapsRequired = 2
        let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleSingleTap))
        // Tap 1 phải chờ chắc chắn không phải tap 2 — giá của việc phân biệt hai cử chỉ.
        singleTap.require(toFail: doubleTap)
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.maximumNumberOfTouches = 1
        let longPress = UILongPressGestureRecognizer(
            target: self, action: #selector(handleLongPress(_:))
        )

        pinch.addTarget(self, action: #selector(handlePinch(_:)))
        pinch.delegate = self
        viewportPan.addTarget(self, action: #selector(handleViewportPan(_:)))
        viewportPan.minimumNumberOfTouches = 2
        viewportPan.maximumNumberOfTouches = 2
        viewportPan.delegate = self

        addGestureRecognizer(doubleTap)
        addGestureRecognizer(singleTap)
        addGestureRecognizer(pan)
        addGestureRecognizer(longPress)
        addGestureRecognizer(pinch)
        addGestureRecognizer(viewportPan)
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) { fatalError("init(coder:) is not supported") }

    // Đang có cử chỉ đổi khung nhìn (pinch / pan hai ngón) hay không. Trong lúc đó
    // các nhánh một ngón vẫn nhận sự kiện — UIKit không huỷ chúng — nên phải tự
    // ngưng di con trỏ, kẻo ngón thứ nhất của cú pinch kéo chuột host đi theo.
    private var isTransforming: Bool {
        pinch.state == .began || pinch.state == .changed ||
            viewportPan.state == .began || viewportPan.state == .changed
    }

    /// Vẽ lại con trỏ theo khung hiện tại. StreamView gọi sau mỗi lần cập nhật để
    /// mũi tên bám khung khi zoom/pan/xoay máy đổi nó.
    func refreshCursor() {
        guard let transform, transform.viewport.width > 0 else {
            cursorView.isHidden = true
            return
        }
        cursorView.isHidden = false
        // Đỉnh mũi tên của "cursorarrow" nằm ở góc trên-trái icon -> origin đặt đúng
        // vị trí con trỏ.
        cursorView.frame.origin = transform.contentToScreen(cursor)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        refreshCursor()
    }

    private func moveCursor(by delta: CGPoint) {
        guard let transform else { return }
        let rect = transform.videoRect
        guard rect.width > 0, rect.height > 0 else { return }
        cursor = CGPoint(
            x: clampUnit(cursor.x + delta.x / rect.width),
            y: clampUnit(cursor.y + delta.y / rect.height)
        )
        // Đang phóng to thì con trỏ dễ chạy ra ngoài phần đang nhìn thấy — kéo khung
        // theo nó. Ở mức zoom = 1 pan luôn bị kẹp về 0 nên hàm này không làm gì.
        transform.ensureVisible(cursor, margin: autoPanMargin)
        refreshCursor()
        sendMove()
    }

    private func clampUnit(_ value: CGFloat) -> CGFloat { min(max(value, 0), 1) }

    // Con trỏ đã ở hệ 0..1 của màn hình PC nên gửi đi chỉ là một phép nhân.
    private func sendMove() {
        model?.mouseMove(
            nx: Int32((cursor.x * 65535).rounded()),
            ny: Int32((cursor.y * 65535).rounded())
        )
    }

    // Host cũng có người dùng thật di chuột được — gửi lại vị trí con trỏ ngay
    // trước mỗi cú click để click rơi đúng chỗ con trỏ đang hiển thị.
    private func click(_ button: MouseButton) {
        sendMove()
        model?.mouseButton(button, down: true)
        model?.mouseButton(button, down: false)
    }

    @objc private func handleSingleTap() { click(.left) }

    @objc private func handleDoubleTap() { click(.right) }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        let location = gesture.location(in: self)
        switch gesture.state {
        case .began:
            lastDragLocation = location
        case .changed:
            // Mốc vẫn phải cập nhật kể cả lúc đang pinch, nếu không thì pinch xong
            // con trỏ nhảy đúng bằng quãng ngón tay đã đi trong lúc đó.
            if !isTransforming {
                moveCursor(by: CGPoint(
                    x: location.x - lastDragLocation.x,
                    y: location.y - lastDragLocation.y
                ))
            }
            lastDragLocation = location
        default:
            break
        }
    }

    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        let location = gesture.location(in: self)
        switch gesture.state {
        case .began:
            guard !isTransforming else { return }
            lastDragLocation = location
            sendMove()
            model?.mouseButton(.left, down: true)
            holdingLeft = true
        case .changed:
            if holdingLeft, !isTransforming {
                moveCursor(by: CGPoint(
                    x: location.x - lastDragLocation.x,
                    y: location.y - lastDragLocation.y
                ))
            }
            lastDragLocation = location
        case .ended, .cancelled, .failed:
            if holdingLeft {
                model?.mouseButton(.left, down: false)
                holdingLeft = false
            }
        default:
            break
        }
    }

    // Pinch: `scale` là luỹ kế nên trả về 1 sau mỗi bước để nó thành hệ số GIA TĂNG,
    // đúng thứ zoomBy nhận. Tâm hai ngón là điểm neo — chỗ dưới ngón phải đứng yên.
    @objc private func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        guard let transform else { return }
        switch gesture.state {
        case .began:
            gesture.scale = 1
        case .changed:
            transform.zoomBy(gesture.scale, focus: gesture.location(in: self))
            gesture.scale = 1
            pushCursorIntoView()
        default:
            break
        }
    }

    @objc private func handleViewportPan(_ gesture: UIPanGestureRecognizer) {
        guard let transform, gesture.state == .changed else { return }
        let translation = gesture.translation(in: self)
        gesture.setTranslation(.zero, in: self)
        transform.panBy(translation)
        pushCursorIntoView()
    }

    // Khung là ý muốn của người dùng, con trỏ mới là thứ phải nhường: để nó bị mép
    // khung đẩy đi thay vì kéo khung về theo nó. KHÔNG gửi gì — chuột bên PC chỉ
    // nhúc nhích khi người dùng thật sự rê/bấm, và lúc đó toạ độ tuyệt đối được gửi
    // kèm nên hai bên không lệch.
    private func pushCursorIntoView() {
        guard let transform else { return }
        cursor = transform.clampToVisible(cursor, margin: autoPanMargin)
        refreshCursor()
    }
}

extension TouchCaptureUIView: UIGestureRecognizerDelegate {
    // Pinch và pan hai ngón phải chạy CÙNG LÚC (vừa phóng vừa dời là một cử chỉ), và
    // chúng còn phải chen ngang được một cú rê một ngón đang dở — mặc định UIKit
    // chặn cái sau, và khi đó đặt hai ngón xuống giữa lúc đang rê là không zoom được.
    // Con trỏ không bị kéo theo nhờ `isTransforming`.
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
    ) -> Bool {
        isViewportGesture(gestureRecognizer) || isViewportGesture(other)
    }

    private func isViewportGesture(_ gesture: UIGestureRecognizer) -> Bool {
        gesture === pinch || gesture === viewportPan
    }
}
