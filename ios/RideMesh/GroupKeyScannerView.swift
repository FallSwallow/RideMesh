import AVFoundation
import SwiftUI

struct GroupKeyScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void
    let onError: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        ScannerController(onScan: onScan, onError: onError)
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) { }

    static func dismantleUIViewController(_ controller: ScannerController, coordinator: ()) {
        controller.stop()
    }
}

final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let onScan: (String) -> Void
    private let onError: (String) -> Void
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.example.ridemesh.qr-camera")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var completed = false
    private var stopped = false

    init(onScan: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        self.onScan = onScan
        self.onError = onError
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
                if allowed { self?.configure() }
                else { self?.reportError("請在系統設定中允許相機權限") }
            }
        default:
            reportError("請在系統設定中允許相機權限")
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stop()
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.stopped = true
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func configure() {
        sessionQueue.async { [weak self] in
            guard let self, !self.stopped else { return }
            guard let camera = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: camera) else {
                self.reportError("此裝置沒有可用的相機")
                return
            }
            self.session.beginConfiguration()
            guard self.session.canAddInput(input) else {
                self.session.commitConfiguration()
                self.reportError("無法啟動相機")
                return
            }
            self.session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard self.session.canAddOutput(output) else {
                self.session.commitConfiguration()
                self.reportError("無法啟動 QR Code 掃描")
                return
            }
            self.session.addOutput(output)
            self.session.commitConfiguration()
            guard output.availableMetadataObjectTypes.contains(.qr) else {
                self.reportError("此相機無法掃描 QR Code")
                return
            }
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                let preview = AVCaptureVideoPreviewLayer(session: self.session)
                preview.videoGravity = .resizeAspectFill
                preview.frame = self.view.bounds
                self.view.layer.insertSublayer(preview, at: 0)
                self.previewLayer = preview
            }
            self.session.startRunning()
        }
    }

    private func reportError(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.completed else { return }
            self.completed = true
            self.onError(message)
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !completed,
              let code = objects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject })
                .first(where: { $0.type == .qr })?.stringValue else { return }
        completed = true
        onScan(code)
    }
}
