package libcore

import (
	"fmt"
	"reflect"
	"testing"
)

func TestPlanSubscriptionUpdatePreservesDuplicateNames(t *testing.T) {
	plan := planSubscriptionUpdate(
		[]subscriptionIncoming{
			{Name: "same", Identity: "second"},
			{Name: "same", Identity: "first"},
			{Name: "new", Identity: "new"},
		},
		[]subscriptionExisting{
			{ID: 1, Name: "same", UserOrder: 2, Identity: "first"},
			{ID: 2, Name: "same", UserOrder: 1, Identity: "second"},
			{ID: 3, Name: "old", UserOrder: 3, Identity: "old"},
		},
	)
	if got := []int64{*plan.Actions[0].ExistingID, *plan.Actions[1].ExistingID}; !reflect.DeepEqual(got, []int64{2, 1}) {
		t.Fatalf("unexpected matches: %v", got)
	}
	if plan.Actions[0].Action != "unchanged" || plan.Actions[1].Action != "unchanged" || plan.Actions[2].Action != "add" {
		t.Fatalf("unexpected actions: %#v", plan.Actions)
	}
	if !reflect.DeepEqual(plan.DeletionIDs, []int64{3}) {
		t.Fatalf("unexpected deletions: %v", plan.DeletionIDs)
	}
}

func TestPlanSubscriptionUpdateUsesStableIdentityAndOrder(t *testing.T) {
	plan := planSubscriptionUpdate(
		[]subscriptionIncoming{
			{Name: "renamed first", Identity: "duplicate"},
			{Name: "renamed second", Identity: "duplicate"},
		},
		[]subscriptionExisting{
			{ID: 11, Name: "old second", UserOrder: 2, Identity: "duplicate"},
			{ID: 12, Name: "old first", UserOrder: 1, Identity: "duplicate"},
		},
	)
	if got := []int64{*plan.Actions[0].ExistingID, *plan.Actions[1].ExistingID}; !reflect.DeepEqual(got, []int64{12, 11}) {
		t.Fatalf("unexpected deterministic matches: %v", got)
	}
	if plan.Actions[0].Action != "update" || plan.Actions[1].Action != "update" {
		t.Fatalf("renamed nodes must be updated: %#v", plan.Actions)
	}
}

func TestPlanSubscriptionUpdatePrefersSameNameForEqualDuplicate(t *testing.T) {
	plan := planSubscriptionUpdate(
		[]subscriptionIncoming{{Name: "keep me", Identity: "duplicate"}},
		[]subscriptionExisting{
			{ID: 21, Name: "older duplicate", UserOrder: 1, Identity: "duplicate"},
			{ID: 22, Name: "keep me", UserOrder: 2, Identity: "duplicate"},
		},
	)
	if plan.Actions[0].ExistingID == nil || *plan.Actions[0].ExistingID != 22 || plan.Actions[0].Action != "reorder" {
		t.Fatalf("same-name duplicate was not preferred: %#v", plan.Actions[0])
	}
	if !reflect.DeepEqual(plan.DeletionIDs, []int64{21}) {
		t.Fatalf("unexpected deletions: %v", plan.DeletionIDs)
	}
}

func TestPlanSubscriptionUpdateHandlesLargeDuplicateSet(t *testing.T) {
	const size = 10_000
	incoming := make([]subscriptionIncoming, size)
	existing := make([]subscriptionExisting, size)
	for index := 0; index < size; index++ {
		id := int64(index + 1)
		incoming[index] = subscriptionIncoming{Name: fmt.Sprintf("renamed %d", id), Identity: "duplicate"}
		existing[size-index-1] = subscriptionExisting{
			ID: id, Name: fmt.Sprintf("old %d", id), UserOrder: id, Identity: "duplicate",
		}
	}
	plan := planSubscriptionUpdate(incoming, existing)
	if len(plan.Actions) != size || len(plan.DeletionIDs) != 0 {
		t.Fatalf("unexpected large plan size: actions=%d deletions=%d", len(plan.Actions), len(plan.DeletionIDs))
	}
	if *plan.Actions[0].ExistingID != 1 || *plan.Actions[size-1].ExistingID != size {
		t.Fatalf("large duplicate plan is not deterministic")
	}
}

func TestPlanSubscriptionUpdateJSON(t *testing.T) {
	result, err := PlanSubscriptionUpdate(`{"incoming":[{"name":"one","identity":"new"}],"existing":[{"id":7,"name":"one","user_order":1,"identity":"old"}]}`)
	if err != nil {
		t.Fatal(err)
	}
	if result != `{"actions":[{"incoming_index":0,"existing_id":7,"action":"update","user_order":1}],"deletion_ids":[]}` {
		t.Fatalf("unexpected JSON response: %s", result)
	}
}

func TestPlanAutoSwitchCandidatesBoundsLargeSubscription(t *testing.T) {
	candidates := make([]autoSwitchCandidate, 10_000)
	for index := range candidates {
		id := int64(index + 1)
		candidate := autoSwitchCandidate{ID: id}
		if id <= 100 {
			candidate.Status = 1
			candidate.LatencyMS = int(id)
		}
		candidates[index] = candidate
	}
	selection, err := planAutoSwitchCandidates(autoSwitchRequest{
		Candidates: candidates, SelectedID: 9_999, Limit: 64, KnownFastLimit: 48,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(selection.IDs) != 64 || selection.IDs[0] != 9_999 {
		t.Fatalf("unexpected bounded selection: %v", selection.IDs)
	}
	for index, id := range selection.IDs[1:49] {
		if id != int64(index+1) {
			t.Fatalf("known-fast order is unstable: %v", selection.IDs[1:49])
		}
	}
}

func TestPlanAutoSwitchCandidatesRotatesExplorationWindow(t *testing.T) {
	candidates := make([]autoSwitchCandidate, 113)
	for index := range candidates {
		id := int64(index + 1)
		candidates[index] = autoSwitchCandidate{ID: id}
		if id <= 49 {
			candidates[index].Status = 1
			candidates[index].LatencyMS = int(id)
		}
	}
	offset := 0
	explored := make(map[int64]struct{})
	for range 5 {
		selection, err := planAutoSwitchCandidates(autoSwitchRequest{
			Candidates: candidates, SelectedID: 1, ExplorationOffset: offset,
			Limit: 64, KnownFastLimit: 48,
		})
		if err != nil {
			t.Fatal(err)
		}
		for _, id := range selection.IDs[49:] {
			explored[id] = struct{}{}
		}
		offset = selection.NextExplorationOffset
	}
	if len(explored) != 64 {
		t.Fatalf("exploration did not cover the pool: %d", len(explored))
	}
}

func TestSelectBestLatency(t *testing.T) {
	best, err := SelectBestLatency(`[{"id":9,"latency_ms":50},{"id":7,"latency_ms":50},{"id":3,"latency_ms":0}]`)
	if err != nil {
		t.Fatal(err)
	}
	if best != 7 {
		t.Fatalf("unexpected best candidate: %d", best)
	}
	empty, err := SelectBestLatency(`[{"id":3,"latency_ms":0}]`)
	if err != nil || empty != 0 {
		t.Fatalf("failed results must not select a candidate: %d, %v", empty, err)
	}
}
