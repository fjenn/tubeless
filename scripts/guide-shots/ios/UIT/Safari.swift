import XCTest

// Drives iPhone Safari through the bookmark install and saves screenshots + element frames for ios.py.
// xcodegen generate && TEST_RUNNER_OUT=<dir> TEST_RUNNER_PFX=pl_ xcodebuild test -project Guide.xcodeproj -scheme UIT \
//   -destination id=<simulator> -only-testing:UIT/Safari/testGuide   (open the install page in Safari first; PFX pd_ for dark)
final class Safari: XCTestCase {
  let out = ProcessInfo.processInfo.environment["OUT"] ?? "/tmp"
  let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
  func shot(_ n: String) {
    let d = XCUIScreen.main.screenshot().pngRepresentation
    try? d.write(to: URL(fileURLWithPath: out + "/" + n + ".png"))
  }
  func dump(_ n: String) { try? safari.debugDescription.write(toFile: out + "/" + n + ".txt", atomically: true, encoding: .utf8) }
  func frame(_ n: String, _ e: XCUIElement) {
    let f = e.frame; let s = "\(f.minX),\(f.minY),\(f.width),\(f.height)\n"
    if let h = FileHandle(forWritingAtPath: out + "/frames.txt") { h.seekToEndOfFile(); h.write((n + " " + s).data(using: .utf8)!); h.closeFile() }
    else { try? (n + " " + s).write(toFile: out + "/frames.txt", atomically: true, encoding: .utf8) }
  }

  var pfx: String { ProcessInfo.processInfo.environment["PFX"] ?? "p_" }
  func snap(_ n: String, _ els: [(String, XCUIElement)] = []) {
    sleep(1); shot(pfx + n); for (k, e) in els where e.exists { frame(pfx + n + ":" + k, e) }
  }
  func dismissNoise() {
    for l in ["Continue", "Not Now"] where safari.buttons[l].firstMatch.exists { safari.buttons[l].firstMatch.tap(); sleep(1) }
  }
  func testGuide() {
    safari.activate(); sleep(2)
    // clean start: no Tubeless favorite left from an earlier run
    let addr = safari.textFields["TabBarItemTitle"]
    if addr.exists { addr.tap(); sleep(2); dismissNoise()
      while safari.cells["Tubeless"].firstMatch.exists { safari.cells["Tubeless"].firstMatch.press(forDuration: 1.2); sleep(1); safari.buttons["Delete"].firstMatch.tap(); sleep(1) }
      safari.buttons["CancelBarItemButton"].tap(); sleep(1) }
    // 1: copy
    let copy = safari.buttons["Copy the bookmark"]
    snap("1a", [("copy", copy)])
    copy.tap(); snap("1b", [("copy", copy)])
    // 2: page menu, Add Bookmark to..., title, Save
    snap("2a", [("menu", safari.buttons["MoreMenuButton"])])
    safari.buttons["MoreMenuButton"].tap(); sleep(1)
    snap("2b", [("addto", safari.buttons["Add Bookmark to…"]), ("menu", safari.buttons["MoreMenuButton"])])
    safari.buttons["Add Bookmark to…"].tap(); sleep(2); dismissNoise()
    let t = safari.textFields["BookmarkInfoTitleTextField"]
    t.buttons["Clear text"].tap(); t.typeText("Tubeless")
    snap("2c", [("title", t), ("save", safari.buttons["BookmarkInfoSaveButton"])])
    safari.buttons["BookmarkInfoSaveButton"].tap(); sleep(2)
    // 3: address bar, Favorites, hold Tubeless, Edit, paste the address, Save
    addr.tap(); sleep(2); dismissNoise()
    let fav = safari.cells["Tubeless"].firstMatch
    snap("3a", [("fav", fav), ("addr", safari.textFields["URL"])])
    fav.press(forDuration: 1.2); sleep(1)
    snap("3b", [("edit", safari.buttons["ContextMenuEditButton"])])
    safari.buttons["ContextMenuEditButton"].tap(); sleep(2); dismissNoise()
    let a = safari.textFields["BookmarkInfoAddressTextField"]
    snap("3c", [("addrfield", a), ("title", safari.textFields["BookmarkInfoTitleTextField"])])
    a.tap(); sleep(1)
    a.buttons["Clear text"].firstMatch.tap(); sleep(1)
    a.press(forDuration: 1.0); sleep(1)
    let paste = safari.menuItems["Paste"].firstMatch.exists ? safari.menuItems["Paste"].firstMatch : safari.buttons["Paste"].firstMatch
    snap("3d", [("addrfield", a), ("paste", paste)])
    paste.tap(); sleep(1)
    snap("3e", [("addrfield", a), ("save", safari.buttons["BookmarkInfoSaveButton"])])
    safari.buttons["BookmarkInfoSaveButton"].tap(); sleep(1)
    if safari.buttons["CancelBarItemButton"].exists { safari.buttons["CancelBarItemButton"].tap(); sleep(1) }
    // 4: open YouTube for Tubeless
    let go = safari.links["Open YouTube for Tubeless"]
    go.swipeUp(); sleep(1)
    snap("4a", [("go", go)])
    go.tap(); sleep(5)
    snap("4b", [("addr", safari.textFields["TabBarItemTitle"])])
    // 5: address bar, tap Tubeless
    safari.textFields["TabBarItemTitle"].tap(); sleep(2); dismissNoise()
    snap("5a", [("fav", safari.cells["Tubeless"].firstMatch)])
    safari.cells["Tubeless"].firstMatch.tap(); sleep(10)
    snap("5b")
  }
}
