// =============================================================================
// StreamView.swift — màn hình XEM + ĐIỀU KHIỂN.
//
// GIAO DIỆN TRẦN (2026-07-27)
//   Chrome từng dựng trên hệ thiết kế riêng (HUD kính bo pill, chip, chấm sống, biểu
//   đồ RTT). Cả bộ đó đã xoá — giờ chỉ còn `Text`/`Button` dựng sẵn của SwiftUI.
//
// BỐ CỤC: BA HÀNG XẾP DỌC, KHÔNG CHỒNG NHAU
//     [thanh trên]  địa chỉ + dòng số liệu
//     [ô giữa]      video + trackpad phủ đúng ô này
//     [thanh dưới]  phím tắt + Display/Keyboard/End
//   Hai thanh từng là HUD nổi ĐÈ lên video; tách hẳn ra để không có gì nằm chắn lên
//   nội dung đang xem, và rê tay lên thanh nút không làm con trỏ nhảy. Đánh đổi:
//   khung hình mất đúng phần chiều cao của hai thanh.
//
// THANH PHÍM TẮT LÀ THỨ DESKTOP KHÔNG CÓ
//   macOS/Windows bắt trọn bàn phím thật và gửi thẳng, kể cả Esc, Tab, F-key. Bàn
//   phím ảo của iOS KHÔNG có những phím đó, nên hàng nút cuộn ngang ở đây là đường
//   duy nhất tới chúng. Nó không phải bản sao của một thứ trên desktop — nó là cái
//   giá của việc không có bàn phím thật.
//
// NHIỀU MÀN HÌNH: ĐỔI NGAY TẠI ĐÂY
//   Host chia sẻ TẤT CẢ màn hình. Nút "Display" (chỉ hiện khi có >1 nguồn) đổi tại
//   chỗ qua SessionModel.switchSource — không phải thoát phiên rồi kết nối lại.
//
// ZOOM (2026-07-29)
//   Hai ngón để phóng to và rê khung nhìn. Trạng thái + phép toán nằm ở VideoZoom.swift
//   (VideoTransform), dùng CHUNG cho khung hình và trackpad — hai bên tự tính riêng là
//   lệch nhau ngay. Ô giữa đo viewport một lần (GeometryReader) rồi truyền xuống cả hai.
//
// Video sống trong AVSampleBufferDisplayLayer (qua VideoLayerView); SwiftUI chỉ vẽ
// phần chrome, cập nhật mỗi 500ms từ SessionModel.
// =============================================================================
import AVFoundation
import SwiftUI
import UIKit

/// Một phím tắt gửi thẳng sang host — bàn phím ảo không có những phím này.
/// `modVk` != 0 -> tổ hợp (giữ phím bổ trợ rồi gõ phím chính): Ctrl+C, Ctrl+V...
/// Thêm phím mới = thêm một dòng: mã phím ảo Windows + scancode US (bit8 = cờ E0
/// cho phím mở rộng như mũi tên/Del).
private struct Hotkey {
    let label: String
    let vk: Int32
    let scan: Int32
    var modVk: Int32 = 0
    var modScan: Int32 = 0
}

// Không đưa Alt+Tab/phím Win vào: chúng đổi ngữ cảnh trên máy host, host sẽ ngừng
// nhận input.
private let kHotkeys: [Hotkey] = [
    Hotkey(label: "Esc", vk: 0x1B, scan: 0x01),
    Hotkey(label: "Tab", vk: 0x09, scan: 0x0F),
    Hotkey(label: "Enter", vk: 0x0D, scan: 0x1C),
    Hotkey(label: "↑", vk: 0x26, scan: 0x148),
    Hotkey(label: "↓", vk: 0x28, scan: 0x150),
    Hotkey(label: "←", vk: 0x25, scan: 0x14B),
    Hotkey(label: "→", vk: 0x27, scan: 0x14D),
    Hotkey(label: "Del", vk: 0x2E, scan: 0x153),
    Hotkey(label: "Ctrl+C", vk: 0x43, scan: 0x2E, modVk: 0x11, modScan: 0x1D),
    Hotkey(label: "Ctrl+V", vk: 0x56, scan: 0x2F, modVk: 0x11, modScan: 0x1D),
]

struct StreamView: View {
    @Bindable var model: SessionModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var layer: AVSampleBufferDisplayLayer?
    @State private var keyboardOn = false
    @State private var pickerOpen = false
    // Zoom/pan của khung hình. Ở ĐÂY chứ không nằm trong TouchInputView vì cả khung
    // hình lẫn trackpad phải nhìn vào CÙNG một khung — xem VideoZoom.swift.
    @State private var transform = VideoTransform()

    private var streaming: Bool { model.phase == .streaming }

    var body: some View {
        VStack(spacing: 0) {
            statusBar
            videoArea
            bottomBar
        }
        .background(Color.black)
        // Không đẩy/nén layout khi bàn phím ảo mở — bàn phím đè lên video, khung hình
        // đứng yên (người dùng chọn vậy).
        .ignoresSafeArea(.keyboard)
        .onChange(of: scenePhase) { _, newPhase in
            switch newPhase {
            case .background:
                releaseLayer()
            case .active:
                if let layer {
                    DeskhubClient.setLayer(layer)
                }
            case .inactive:
                break
            @unknown default:
                break
            }
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            model.streamViewAppeared()
        }
        // Đổi màn hình = nguồn khác, có thể khác cả tỉ lệ; giữ zoom cũ là vừa đổi
        // xong đã thấy một góc lạ hoắc.
        .onChange(of: model.currentSourceId) { _, _ in transform.reset() }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            keyboardOn = false
            releaseLayer()
            model.streamViewDisappeared()
        }
        .statusBarHidden()
    }

    // MARK: - Thanh trên

    private var statusBar: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(hostTitle)
                .font(.caption)
                .foregroundStyle(.white)
                .lineLimit(1)
                .truncationMode(.middle)
            if streaming, !model.statusLine.isEmpty {
                Text(model.statusLine)
                    .font(.caption)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    private var hostTitle: String {
        guard model.videoWidth > 0 else { return model.address }
        return "\(model.address) — \(model.videoWidth)×\(model.videoHeight)"
    }

    // MARK: - Ô giữa: video

    private var videoArea: some View {
        // GeometryReader chỉ để ĐO: viewport là thứ VideoTransform cần để dựng khung,
        // và đo ở đây thì cả khung hình lẫn trackpad dùng chung đúng một con số.
        GeometryReader { geo in
            ZStack {
                VideoLayerView { newLayer in
                    layer = newLayer
                    DeskhubClient.setLayer(newLayer)
                }
                .aspectRatio(aspectRatio, contentMode: .fit)
                // Zoom = phép biến hình của chính layer video, không đụng gì tới đường
                // giải mã: CMSampleBuffer vẫn nguyên độ phân giải, bộ ghép hình lấy
                // mẫu ở tỉ lệ cuối. scaleEffect neo ở TÂM — trùng công thức videoRect.
                .scaleEffect(transform.zoom)
                .offset(x: transform.pan.x, y: transform.pan.y)

                // Trackpad phủ trọn ô giữa — gồm vùng đen letterbox quanh video: rê tay ở
                // đâu cũng di được chuột (trackpad chạy theo delta). Con trỏ và toạ độ gửi
                // đi bám khung video thật, lấy từ `transform`.
                if streaming {
                    TouchInputView(model: model, transform: transform)
                }

                // View hứng phím: vô hình, chỉ tồn tại để giữ first responder.
                // allowsHitTesting(false): không được nuốt cú chạm của lớp touch.
                KeyInputView(model: model, active: $keyboardOn)
                    .frame(width: 1, height: 1)
                    .opacity(0)
                    .allowsHitTesting(false)

                // Lớp phủ trạng thái nằm TRONG ô video, không che hai thanh.
                if !model.endReason.isEmpty {
                    endedOverlay
                } else if !streaming {
                    connectingOverlay
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            // Khung đang phóng to TRÀN ra ngoài ô video — cắt đúng ở đây, kẻo nó đè
            // lên thanh trạng thái và thanh phím tắt.
            .clipped()
            .onAppear {
                transform.setViewport(geo.size)
                transform.setAspect(aspectRatio)
            }
            .onChange(of: geo.size) { _, size in transform.setViewport(size) }
            .onChange(of: aspectRatio) { _, value in transform.setAspect(value) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var aspectRatio: CGFloat {
        let width = CGFloat(model.videoWidth)
        let height = CGFloat(model.videoHeight)
        guard width > 0, height > 0 else { return 16.0 / 9.0 }
        return width / height
    }

    // MARK: - Thanh dưới: phím tắt + điều khiển

    private var bottomBar: some View {
        VStack(spacing: 8) {
            // Phím tắt cuộn ngang: cả hàng dài hơn bề ngang máy.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(kHotkeys, id: \.label) { hotkey in
                        Button(hotkey.label) { send(hotkey) }
                            .buttonStyle(.bordered)
                    }
                }
                .padding(.horizontal, 12)
            }
            .disabled(!streaming)
            .opacity(streaming ? 1 : 0.45)

            HStack(spacing: 10) {
                Button(keyboardOn ? "Hide keyboard" : "Keyboard") { keyboardOn.toggle() }
                    .buttonStyle(.bordered)
                    .disabled(!streaming)

                // Chỉ hiện khi có cái để đổi: host một màn hình thì nút này là một câu
                // hỏi không có câu trả lời.
                if model.sources.count > 1 {
                    Button("Display") { pickerOpen = true }
                        .buttonStyle(.bordered)
                }

                // Đường về khi phóng sâu rồi lạc: chỉ hiện đúng lúc đang zoom, và nhãn
                // mang luôn mức zoom hiện tại nên không cần thêm chỗ nào hiển thị nó.
                if transform.zoomedIn {
                    Button(String(format: "Fit %.1f×", Double(transform.zoom))) { transform.reset() }
                        .buttonStyle(.bordered)
                }

                Spacer()

                Button("End") { model.disconnect() }
                    .buttonStyle(.borderedProminent)
            }
            .padding(.horizontal, 12)
        }
        .padding(.vertical, 6)
        .confirmationDialog("Display", isPresented: $pickerOpen, titleVisibility: .visible) {
            ForEach(model.sources) { source in
                Button(sourceLabel(source)) { model.switchSource(to: source.id) }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func sourceLabel(_ source: Source) -> String {
        let name = source.name.isEmpty ? "Source \(source.id)" : source.name
        let mark = source.id == model.currentSourceId ? "✓ " : ""
        return "\(mark)\(name) — \(source.width)×\(source.height)"
    }

    private func send(_ hotkey: Hotkey) {
        if hotkey.modVk != 0 {
            model.keyChord(
                modVk: hotkey.modVk, modScan: hotkey.modScan,
                vk: hotkey.vk, scan: hotkey.scan
            )
        } else {
            model.keyTap(vk: hotkey.vk, scan: hotkey.scan)
        }
    }

    // MARK: - Lớp phủ

    private var connectingOverlay: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text("Connecting to \(model.address)…")
                .foregroundStyle(.white)
        }
    }

    private var endedOverlay: some View {
        VStack(spacing: 12) {
            Text("Session ended")
                .font(.headline)
                .foregroundStyle(.white)
            Text(model.endReason)
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button("Back") { model.disconnect() }
                .buttonStyle(.borderedProminent)
        }
        .padding(24)
    }

    private func releaseLayer() {
        DeskhubClient.setLayer(nil)
    }
}
