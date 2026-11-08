// Draws the TV launcher banner and icon.
// Usage: swift tools/make_art.swift app/src/main/res/drawable-nodpi
import AppKit

let background = NSColor(red: 0x17 / 255.0, green: 0x0F / 255.0, blue: 0x2B / 255.0, alpha: 1)
let foreground = NSColor(red: 0xF6 / 255.0, green: 0xF3 / 255.0, blue: 0xFF / 255.0, alpha: 1)
let accent = NSColor(red: 1, green: 0x5C / 255.0, blue: 0x8A / 255.0, alpha: 1)
let font = NSFont.systemFont(ofSize: 92, weight: .black)

func save(_ name: String, width: Int, height: Int, draw: () -> Void) {
    let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: false)
    background.setFill()
    NSRect(x: 0, y: 0, width: width, height: height).fill()
    draw()
    NSGraphicsContext.current?.flushGraphics()
    let bitmap = NSBitmapImageRep(cgImage: context.makeImage()!)
    try! bitmap.representation(using: .png, properties: [:])!
        .write(to: URL(fileURLWithPath: CommandLine.arguments[1] + "/" + name))
}

/// A speech bubble with a question mark: someone asking "who?".
func bubble(_ rect: NSRect) {
    let body = NSBezierPath(roundedRect: rect, xRadius: rect.height * 0.3, yRadius: rect.height * 0.3)
    let tail = NSBezierPath()
    tail.move(to: CGPoint(x: rect.minX + rect.width * 0.22, y: rect.minY + 2))
    tail.line(to: CGPoint(x: rect.minX + rect.width * 0.12, y: rect.minY - rect.height * 0.28))
    tail.line(to: CGPoint(x: rect.minX + rect.width * 0.42, y: rect.minY + 2))
    tail.close()
    accent.setFill()
    body.fill()
    tail.fill()
    let mark = NSAttributedString(string: "?", attributes: [
        .font: NSFont.systemFont(ofSize: rect.height * 0.78, weight: .black), .foregroundColor: background,
    ])
    let size = mark.size()
    mark.draw(at: CGPoint(x: rect.midX - size.width / 2, y: rect.midY - size.height / 2))
}

save("banner.png", width: 640, height: 360) {
    let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: foreground]
    for (i, word) in ["QUEM", "DIRIA"].enumerated() {
        let text = NSAttributedString(string: word, attributes: attributes)
        text.draw(at: CGPoint(x: 64, y: 190 - CGFloat(i) * 104))
    }
    bubble(NSRect(x: 420, y: 150, width: 160, height: 120))
}

save("icon.png", width: 360, height: 360) {
    bubble(NSRect(x: 50, y: 110, width: 260, height: 200))
}
