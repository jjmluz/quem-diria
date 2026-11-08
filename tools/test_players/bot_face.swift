import AppKit
// Usage: swift bot_face.swift <out.jpg> <emoji> <r> <g> <b>
let a = CommandLine.arguments
let size = 480
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
NSColor(red: Double(a[3])!, green: Double(a[4])!, blue: Double(a[5])!, alpha: 1).setFill()
NSRect(x: 0, y: 0, width: size, height: size).fill()
let s = NSAttributedString(string: a[2], attributes: [.font: NSFont.systemFont(ofSize: 300)])
let d = s.size()
s.draw(at: CGPoint(x: (CGFloat(size) - d.width) / 2, y: (CGFloat(size) - d.height) / 2))
NSGraphicsContext.current?.flushGraphics()
try! NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .jpeg, properties: [.compressionFactor: 0.85])!
    .write(to: URL(fileURLWithPath: a[1]))
