// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.

package config

import "testing"

func TestIsDeviceID(t *testing.T) {
	for _, s := range []string{"0000000000000000", "d09e503392558b2b", "D09E503392558B2B"} {
		if !isDeviceID(s) {
			t.Errorf("isDeviceID(%q) = false, want true", s)
		}
	}
	// "unknown" was the old default and is exactly the value the VPS rejects.
	for _, s := range []string{"", "unknown", "d09e503392558b2", "d09e503392558b2bb", "zzzzzzzzzzzzzzzz"} {
		if isDeviceID(s) {
			t.Errorf("isDeviceID(%q) = true, want false", s)
		}
	}
}
