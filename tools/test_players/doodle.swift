import AppKit
// Draws on a selfie the way a player might on their phone, for the test bots' drawings.
// Usage: swift doodle.swift <selfie.jpg> <out.jpg> <style 0-5>
// Styles: glasses and moustache, pirate, crown, cat, devil, clown. The positions suit a face looking straight at
// the camera and filling the square (as the AI-generated faces used for the README's screenshots do).
let a = CommandLine.arguments
let size: CGFloat = 480
let ctx = CGContext(data: nil, width: Int(size), height: Int(size), bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
let selfie = NSImage(contentsOfFile: a[1])!.cgImage(forProposedRect: nil, context: nil, hints: nil)!
ctx.draw(selfie, in: CGRect(x: 0, y: 0, width: size, height: size))
// From here on, (0, 0) is the top left and coordinates are fractions of the side, like the phone's canvas.
ctx.translateBy(x: 0, y: size)
ctx.scaleBy(x: size, y: -size)
ctx.setLineCap(.round)
ctx.setLineJoin(.round)

// The phone's pen colours (game.js, PEN_COLOURS).
func colour(_ hex: Int) -> CGColor {
    CGColor(srgbRed: CGFloat(hex >> 16 & 255) / 255, green: CGFloat(hex >> 8 & 255) / 255, blue: CGFloat(hex & 255) / 255, alpha: 1)
}
let black = colour(0x111111), white = colour(0xFFFFFF), pink = colour(0xFF5C8A), yellow = colour(0xFFC83D)
let teal = colour(0x2EC4B6), blue = colour(0x4D8DFF), purple = colour(0xA974FF), brown = colour(0x7B4B2A)

/** A finger isn't steady: each point wobbles a little. */
func wobble(_ p: CGPoint) -> CGPoint {
    CGPoint(x: p.x + .random(in: -0.004...0.004), y: p.y + .random(in: -0.004...0.004))
}

func line(_ points: [(CGFloat, CGFloat)], _ c: CGColor, width: CGFloat = 0.025, closed: Bool = false, fill: CGColor? = nil) {
    let path = CGMutablePath()
    path.addLines(between: points.map { wobble(CGPoint(x: $0.0, y: $0.1)) })
    if closed { path.closeSubpath() }
    if let fill {
        ctx.addPath(path)
        ctx.setFillColor(fill)
        ctx.fillPath()
    }
    ctx.addPath(path)
    ctx.setStrokeColor(c)
    ctx.setLineWidth(width)
    ctx.strokePath()
}

func circle(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ c: CGColor, width: CGFloat = 0.025, fill: CGColor? = nil) {
    line((0...24).map { i in
        let t = CGFloat(i) / 24 * 2 * .pi
        return (x + r * cos(t), y + r * sin(t))
    }, c, width: width, closed: true, fill: fill)
}

func curve(_ from: (CGFloat, CGFloat), _ to: (CGFloat, CGFloat), bend: CGFloat, _ c: CGColor, width: CGFloat = 0.025) {
    line((0...16).map { i in
        let t = CGFloat(i) / 16
        let x = from.0 + (to.0 - from.0) * t, y = from.1 + (to.1 - from.1) * t
        return (x, y + bend * 4 * t * (1 - t))
    }, c, width: width)
}

switch Int(a[3])! % 6 {
case 0: // glasses and a curly moustache
    circle(0.36, 0.47, 0.08, black)
    circle(0.63, 0.47, 0.08, black)
    curve((0.44, 0.46), (0.55, 0.46), bend: -0.03, black)
    line([(0.28, 0.45), (0.18, 0.42)], black)
    line([(0.71, 0.45), (0.81, 0.42)], black)
    curve((0.5, 0.66), (0.33, 0.64), bend: 0.05, brown, width: 0.04)
    curve((0.5, 0.66), (0.67, 0.64), bend: 0.05, brown, width: 0.04)
    circle(0.31, 0.62, 0.02, brown)
    circle(0.69, 0.62, 0.02, brown)
case 1: // pirate: hat, skull and an eye patch
    line([(0.12, 0.25), (0.25, 0.04), (0.5, 0.0), (0.75, 0.04), (0.88, 0.25), (0.5, 0.2)], black, closed: true, fill: black)
    circle(0.5, 0.1, 0.035, white, width: 0.015, fill: white)
    line([(0.44, 0.14), (0.56, 0.2)], white, width: 0.015)
    line([(0.56, 0.14), (0.44, 0.2)], white, width: 0.015)
    line([(0.2, 0.33), (0.63, 0.47), (0.86, 0.36)], black, width: 0.015)
    circle(0.63, 0.47, 0.065, black, fill: black)
case 2: // crown and rosy cheeks
    line([(0.25, 0.22), (0.25, 0.04), (0.37, 0.14), (0.5, 0.0), (0.63, 0.14), (0.75, 0.04), (0.75, 0.22)],
         colour(0xE0A800), closed: true, fill: yellow)
    for (x, y, c) in [(0.37, 0.18, pink), (0.5, 0.17, teal), (0.63, 0.18, blue)] {
        circle(x, y, 0.02, c, width: 0.01, fill: c)
    }
    circle(0.3, 0.62, 0.05, pink, width: 0.01, fill: pink)
    circle(0.7, 0.62, 0.05, pink, width: 0.01, fill: pink)
case 3: // cat: ears, nose and whiskers
    line([(0.2, 0.22), (0.24, 0.0), (0.4, 0.13)], black, width: 0.03, fill: pink)
    line([(0.8, 0.22), (0.76, 0.0), (0.6, 0.13)], black, width: 0.03, fill: pink)
    line([(0.46, 0.58), (0.54, 0.58), (0.5, 0.63)], pink, closed: true, fill: pink)
    for y in [0.6, 0.64, 0.68] as [CGFloat] {
        line([(0.4, 0.63), (0.18, y)], black, width: 0.012)
        line([(0.6, 0.63), (0.82, y)], black, width: 0.012)
    }
case 4: // devil: horns and a pointy beard
    let red = colour(0xE63946)
    line([(0.27, 0.2), (0.2, 0.0), (0.37, 0.14)], red, closed: true, fill: red)
    line([(0.73, 0.2), (0.8, 0.0), (0.63, 0.14)], red, closed: true, fill: red)
    curve((0.28, 0.4), (0.44, 0.42), bend: -0.04, black, width: 0.03)
    curve((0.56, 0.42), (0.72, 0.4), bend: -0.04, black, width: 0.03)
    line([(0.42, 0.84), (0.5, 0.99), (0.58, 0.84)], black, closed: true, fill: black)
default: // clown: nose, big smile and rainbow hair
    circle(0.5, 0.59, 0.055, colour(0xE63946), fill: colour(0xE63946))
    curve((0.33, 0.7), (0.67, 0.7), bend: 0.08, pink, width: 0.03)
    for (i, c) in [pink, yellow, teal, blue, purple].enumerated() {
        circle(0.12 + CGFloat(i) * 0.02, 0.15 + CGFloat(i) * 0.07, 0.07, c, width: 0.01, fill: c)
        circle(0.88 - CGFloat(i) * 0.02, 0.15 + CGFloat(i) * 0.07, 0.07, c, width: 0.01, fill: c)
    }
}

try! NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .jpeg, properties: [.compressionFactor: 0.85])!
    .write(to: URL(fileURLWithPath: a[2]))
