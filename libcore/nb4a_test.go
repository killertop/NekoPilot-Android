package libcore

import "testing"

func TestNormalizeLogSizeKB(t *testing.T) {
	for input, want := range map[int32]int32{
		-1:               minLogSizeKB,
		0:                minLogSizeKB,
		minLogSizeKB:     minLogSizeKB,
		1024:             1024,
		maxLogSizeKB:     maxLogSizeKB,
		maxLogSizeKB + 1: maxLogSizeKB,
	} {
		if got := normalizeLogSizeKB(input); got != want {
			t.Fatalf("normalizeLogSizeKB(%d) = %d, want %d", input, got, want)
		}
	}
}
