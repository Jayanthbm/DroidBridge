// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
//
// Pure, platform-independent mapping from characters and modifiers to the 8-byte boot-style
// keyboard input report declared in HIDDescriptor.swift. Kept free of IOBluetooth so it can be
// unit-tested. No Carbon/HIToolbox keyboard-layout API, no Accessibility, no clipboard access.
import Foundation

enum HIDKeyboardMap {
    /// Modifier bit positions in byte 0 of the report (HID usage page 0x07, usages 0xE0...0xE7).
    enum Modifier: UInt8 {
        case leftControl = 0x01
        case leftShift = 0x02
        case leftAlt = 0x04
        case leftGUI = 0x08
        case rightControl = 0x10
        case rightShift = 0x20
        case rightAlt = 0x40
        case rightGUI = 0x80
    }

    /// One key press: an optional key usage plus a modifier bitfield.
    struct KeyPress: Equatable {
        let usage: UInt8
        let modifiers: UInt8
    }

    /// Common single-modifier values, named for callers.
    static let shift = Modifier.leftShift.rawValue
    static let control = Modifier.leftControl.rawValue
    static let alt = Modifier.leftAlt.rawValue
    static let gui = Modifier.leftGUI.rawValue

    /// Maps one character to a HID key usage and the modifier bits needed to type it.
    /// Returns nil for anything not representable on a US HID keyboard (non-ASCII, emoji,
    /// composed/IME characters, control characters other than the named ones).
    static func press(for character: Character) -> KeyPress? {
        guard let ascii = character.asciiValue else { return nil }
        switch character {
        case "a"..."z": return KeyPress(usage: 0x04 + (ascii - 97), modifiers: 0)
        case "A"..."Z": return KeyPress(usage: 0x04 + (ascii - 65), modifiers: shift)
        case "1"..."9": return KeyPress(usage: 0x1E + (ascii - 49), modifiers: 0)
        case "0": return KeyPress(usage: 0x27, modifiers: 0)
        case " ": return KeyPress(usage: 0x2C, modifiers: 0)
        case "\n", "\r": return KeyPress(usage: 0x28, modifiers: 0)   // Enter
        case "\t": return KeyPress(usage: 0x2B, modifiers: 0)          // Tab
        case "\u{08}": return KeyPress(usage: 0x2A, modifiers: 0)      // Backspace
        case "\u{1B}": return KeyPress(usage: 0x29, modifiers: 0)      // Escape
        case "-", "_": return KeyPress(usage: 0x2D, modifiers: character == "_" ? shift : 0)
        case "=", "+": return KeyPress(usage: 0x2E, modifiers: character == "+" ? shift : 0)
        case "[", "{": return KeyPress(usage: 0x2F, modifiers: character == "{" ? shift : 0)
        case "]", "}": return KeyPress(usage: 0x30, modifiers: character == "}" ? shift : 0)
        case "\\", "|": return KeyPress(usage: 0x31, modifiers: character == "|" ? shift : 0)
        case ";", ":": return KeyPress(usage: 0x33, modifiers: character == ":" ? shift : 0)
        case "'", "\"": return KeyPress(usage: 0x34, modifiers: character == "\"" ? shift : 0)
        case "`", "~": return KeyPress(usage: 0x35, modifiers: character == "~" ? shift : 0)
        case ",", "<": return KeyPress(usage: 0x36, modifiers: character == "<" ? shift : 0)
        case ".", ">": return KeyPress(usage: 0x37, modifiers: character == ">" ? shift : 0)
        case "/", "?": return KeyPress(usage: 0x38, modifiers: character == "?" ? shift : 0)
        default: return nil
        }
    }

    /// Builds the 8-byte keyboard report: modifier bits, reserved zero, up to six key usages.
    /// Returns nil if a usage is outside the descriptor's declared range (1...101), if more than
    /// six keys are supplied, or if a duplicate key would violate the array semantics.
    static func report(modifiers: UInt8, keys: [UInt8]) -> Data? {
        guard keys.count <= 6, Set(keys).count == keys.count else { return nil }
        guard keys.allSatisfy({ $0 >= 0x01 && $0 <= 0x65 }) else { return nil }
        var payload = Data(repeating: 0, count: HIDIdentity.keyboardInputLength)
        payload[0] = modifiers
        for (offset, usage) in keys.enumerated() {
            payload[2 + offset] = usage
        }
        return payload
    }

    /// Modifier bitfield for a set of modifier usages (0xE0...0xE7), or nil for an invalid usage.
    static func modifierBits(_ usages: [UInt8]) -> UInt8? {
        var bits: UInt8 = 0
        for usage in usages {
            guard usage >= 0xE0 && usage <= 0xE7 else { return nil }
            bits |= 1 << (usage - 0xE0)
        }
        return bits
    }
}
