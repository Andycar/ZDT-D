// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.

package supervisor

import "testing"

func TestFatalTrackerTalliesTokens(t *testing.T) {
	var f fatalTracker
	// Verbatim shape of the transport's fatal worker line; the prose is Russian
	// on purpose — only the ASCII token may be read.
	f.observe("[ВОРКЕР #1] Фатальная ошибка: FATAL_AUTH: пароль привязан к другому устройству")
	f.observe("[ВОРКЕР #2] Фатальная ошибка: FATAL_AUTH: пароль привязан к другому устройству")
	f.observe("[ВОРКЕР #3] Фатальная ошибка: FATAL_HASH: hash rejected")
	f.observe("STATS|active=0|total=45")

	if got, want := f.summary(), "FATAL_AUTH x2, FATAL_HASH x1"; got != want {
		t.Fatalf("summary = %q, want %q", got, want)
	}
}

func TestFatalTrackerQuietWhenHealthy(t *testing.T) {
	var f fatalTracker
	f.observe("HASH_CHECK|1|ok|TURN urls=2")
	f.observe("[КЛИЕНТ] Device ID: 0000000000000000")
	if got := f.summary(); got != "" {
		t.Fatalf("summary = %q, want empty", got)
	}
}

func TestFatalTrackerNilIsSafe(t *testing.T) {
	var f *fatalTracker
	f.observe("FATAL_AUTH: whatever")
	if got := f.summary(); got != "" {
		t.Fatalf("summary = %q, want empty", got)
	}
}
