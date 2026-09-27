import CoreImage.CIFilterBuiltins
import SwiftUI

struct GroupKeyQRCodeView: View {
    let key: String

    var body: some View {
        VStack(spacing: 20) {
            if let image = makeImage() {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .frame(width: 300, height: 300)
                    .padding(16)
                    .background(.white)
                    .accessibilityLabel("群組金鑰 QR Code")
            } else {
                Text("無法產生 QR Code")
            }
            Text("請讓其他騎士掃描。持有此碼的人可加入頻道。")
                .multilineTextAlignment(.center)
                .font(.footnote)
        }
        .padding()
    }

    private func makeImage() -> UIImage? {
        let generator = CIFilter.qrCodeGenerator()
        generator.message = Data(key.utf8)
        generator.correctionLevel = "M"
        guard let output = generator.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let image = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}
