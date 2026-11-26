// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
import XCTest
@testable import DroidBridge

final class HIDKeyboardMapTests: XCTestCase {
    func testBootReportLayout() {
        let report = HIDKeyboardMap.report(modifiers: 0, keys: [0x52])
        XCTAssertEqual(report?.count, 8)
        XCTAssertEqual(Array(report ?? Data()), [0, 0, 0x52, 0, 0, 0, 0, 0])
    }

    func testModifierByteAndSixKeyArray() {
        let report = HIDKeyboardMap.report(modifiers: HIDKeyboardMap.shift, keys: [0x04, 0x1E, 0x2C])
        XCTAssertEqual(Array(report ?? Data()), [0x02, 0, 0x04, 0x1E, 0x2C, 0, 0, 0])
    }

    func testModifierUsageBitEncoding() {
        XCTAssertEqual(HIDKeyboardMap.modifierBits([0xE0]), 0x01)
        XCTAssertEqual(HIDKeyboardMap.modifierBits([0xE1]), 0x02)
        XCTAssertEqual(HIDKeyboardMap.modifierBits([0xE0, 0xE1, 0xE7]), 0x83)
        XCTAssertNil(HIDKeyboardMap.modifierBits([0x52]))
    }

    func testReportRejectsOutOfRangeAndDuplicateKeys() {
        XCTAssertNil(HIDKeyboardMap.report(modifiers: 0, keys: [0x66]))
        XCTAssertNil(HIDKeyboardMap.report(modifiers: 0, keys: [0]))
        XCTAssertNil(HIDKeyboardMap.report(modifiers: 0, keys: [0x04, 0x04]))
        XCTAssertNil(HIDKeyboardMap.report(modifiers: 0, keys: [0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A]))
    }

    func testLowerCaseAndUpperCaseUseSameUsageWithShift() {
        XCTAssertEqual(HIDKeyboardMap.press(for: "a"), HIDKeyboardMap.KeyPress(usage: 0x04, modifiers: 0))
        XCTAssertEqual(HIDKeyboardMap.press(for: "A"), HIDKeyboardMap.KeyPress(usage: 0x04, modifiers: 0x02))
        XCTAssertEqual(HIDKeyboardMap.press(for: "z"), HIDKeyboardMap.KeyPress(usage: 0x1D, modifiers: 0))
        XCTAssertEqual(HIDKeyboardMap.press(for: "Z"), HIDKeyboardMap.KeyPress(usage: 0x1D, modifiers: 0x02))
    }

    func testDigitsAndWhitespace() {
        XCTAssertEqual(HIDKeyboardMap.press(for: "1"), HIDKeyboardMap.KeyPress(usage: 0x1E, modifiers: 0))
        XCTAssertEqual(HIDKeyboardMap.press(for: "0"), HIDKeyboardMap.KeyPress(usage: 0x27, modifiers: 0))
        XCTAssertEqual(HIDKeyboardMap.press(for: " "), HIDKeyboardMap.KeyPress(usage: 0x2C, modifiers: 0))
        XCTAssertEqual(HIDKeyboardMap.press(for: "\n"), HIDKeyboardMap.KeyPress(usage: 0x28, modifiers: 0))
        XCTAssertEqual(HIDKeyboardMap.press(for: "\t"), HIDKeyboardMap.KeyPress(usage: 0x2B, modifiers: 0))
    }

    func testShiftedPunctuation() {
        XCTAssertEqual(HIDKeyboardMap.press(for: "-"), HIDKeyboardMap.KeyPress(usage: 0x2D, modifiers: 0))
        XCTAssertEqual(HIDKeyboardMap.press(for: "_"), HIDKeyboardMap.KeyPress(usage: 0x2D, modifiers: 0x02))
        XCTAssertEqual(HIDKeyboardMap.press(for: "/"), HIDKeyboardMap.KeyPress(usage: 0x38, modifiers: 0))
        XCTAssertEqual(HIDKeyboardMap.press(for: "?"), HIDKeyboardMap.KeyPress(usage: 0x38, modifiers: 0x02))
    }

    func testUnmappableCharactersRejected() {
        for ch: Character in ["é", "中", "🙂", "\u{00}", "\u{01}"] {
            XCTAssertNil(HIDKeyboardMap.press(for: ch), "\(ch) must not be emitted as a key")
        }
    }

    func testArrowUsagesRemainInDescriptorRange() {
        for usage: UInt8 in [0x52, 0x51, 0x50, 0x4F] {
            XCTAssertNotNil(HIDKeyboardMap.report(modifiers: 0, keys: [usage]))
        }
    }
}
