package libcore

import (
	"container/heap"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
)

type autoSwitchRequest struct {
	Candidates        []autoSwitchCandidate `json:"candidates"`
	SelectedID        int64                 `json:"selected_id"`
	ExplorationOffset int                   `json:"exploration_offset"`
	Limit             int                   `json:"limit"`
	KnownFastLimit    int                   `json:"known_fast_limit"`
}

type autoSwitchCandidate struct {
	ID        int64 `json:"id"`
	Status    int   `json:"status"`
	LatencyMS int   `json:"latency_ms"`
}

type autoSwitchSelection struct {
	IDs                   []int64 `json:"ids"`
	ExploredCount         int     `json:"explored_count"`
	ExplorationPoolSize   int     `json:"exploration_pool_size"`
	NextExplorationOffset int     `json:"next_exploration_offset"`
}

type subscriptionPlanRequest struct {
	Incoming []subscriptionIncoming `json:"incoming"`
	Existing []subscriptionExisting `json:"existing"`
}

type subscriptionIncoming struct {
	Name     string `json:"name"`
	Identity string `json:"identity"`
}

type subscriptionExisting struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	UserOrder int64  `json:"user_order"`
	Identity  string `json:"identity"`
}

type subscriptionAction struct {
	IncomingIndex int    `json:"incoming_index"`
	ExistingID    *int64 `json:"existing_id,omitempty"`
	Action        string `json:"action"`
	UserOrder     int64  `json:"user_order"`
}

type subscriptionPlan struct {
	Actions     []subscriptionAction `json:"actions"`
	DeletionIDs []int64              `json:"deletion_ids"`
}

type subscriptionIdentityAndName struct {
	Identity string
	Name     string
}

type orderedSubscriptionID struct {
	UserOrder int64
	ID        int64
}

type orderedSubscriptionIDs []orderedSubscriptionID

func (ids orderedSubscriptionIDs) Len() int { return len(ids) }

func (ids orderedSubscriptionIDs) Less(i, j int) bool {
	if ids[i].UserOrder == ids[j].UserOrder {
		return ids[i].ID < ids[j].ID
	}
	return ids[i].UserOrder < ids[j].UserOrder
}

func (ids orderedSubscriptionIDs) Swap(i, j int) { ids[i], ids[j] = ids[j], ids[i] }

func (ids *orderedSubscriptionIDs) Push(value any) {
	*ids = append(*ids, value.(orderedSubscriptionID))
}

func (ids *orderedSubscriptionIDs) Pop() any {
	old := *ids
	last := old[len(old)-1]
	*ids = old[:len(old)-1]
	return last
}

// PlanSubscriptionUpdate deterministically matches incoming subscription nodes to persisted
// nodes. The JSON boundary keeps gomobile models small while the matching work stays in the
// already-loaded Go core instead of requiring a second native runtime and JNI bridge.
func PlanSubscriptionUpdate(requestJSON string) (string, error) {
	var request subscriptionPlanRequest
	if err := json.Unmarshal([]byte(requestJSON), &request); err != nil {
		return "", fmt.Errorf("decode subscription update request: %w", err)
	}
	plan := planSubscriptionUpdate(request.Incoming, request.Existing)
	encoded, err := json.Marshal(plan)
	if err != nil {
		return "", fmt.Errorf("encode subscription update plan: %w", err)
	}
	return string(encoded), nil
}

// PlanAutoSwitchCandidates bounds a potentially large subscription before the Android service
// constructs native outbounds. The policy lives beside the proxy core; Kotlin only supplies the
// Room snapshot and consumes the deterministic IDs.
func PlanAutoSwitchCandidates(requestJSON string) (string, error) {
	var request autoSwitchRequest
	if err := json.Unmarshal([]byte(requestJSON), &request); err != nil {
		return "", fmt.Errorf("decode auto-switch request: %w", err)
	}
	selection, err := planAutoSwitchCandidates(request)
	if err != nil {
		return "", err
	}
	encoded, err := json.Marshal(selection)
	if err != nil {
		return "", fmt.Errorf("encode auto-switch selection: %w", err)
	}
	return string(encoded), nil
}

// SelectBestLatency returns zero when no successful measurement exists.
func SelectBestLatency(resultsJSON string) (int64, error) {
	var results []autoSwitchCandidate
	if err := json.Unmarshal([]byte(resultsJSON), &results); err != nil {
		return 0, fmt.Errorf("decode latency results: %w", err)
	}
	var bestID int64
	var bestLatency int
	for _, result := range results {
		if result.ID <= 0 || result.LatencyMS <= 0 {
			continue
		}
		if bestID == 0 || result.LatencyMS < bestLatency ||
			(result.LatencyMS == bestLatency && result.ID < bestID) {
			bestID = result.ID
			bestLatency = result.LatencyMS
		}
	}
	return bestID, nil
}

func planAutoSwitchCandidates(request autoSwitchRequest) (autoSwitchSelection, error) {
	if request.Limit <= 0 || request.Limit > 1024 {
		return autoSwitchSelection{}, errors.New("invalid auto-switch candidate limit")
	}
	if request.KnownFastLimit < 0 || request.KnownFastLimit >= request.Limit {
		return autoSwitchSelection{}, errors.New("invalid known-fast candidate limit")
	}
	seen := make(map[int64]struct{}, len(request.Candidates))
	for _, candidate := range request.Candidates {
		if candidate.ID <= 0 {
			return autoSwitchSelection{}, errors.New("invalid auto-switch candidate ID")
		}
		if _, exists := seen[candidate.ID]; exists {
			return autoSwitchSelection{}, errors.New("duplicate auto-switch candidate ID")
		}
		seen[candidate.ID] = struct{}{}
	}
	if len(request.Candidates) <= request.Limit {
		ids := make([]int64, len(request.Candidates))
		for index, candidate := range request.Candidates {
			ids[index] = candidate.ID
		}
		return autoSwitchSelection{IDs: ids}, nil
	}

	knownFast := make([]autoSwitchCandidate, 0, len(request.Candidates))
	selectedPresent := false
	for _, candidate := range request.Candidates {
		if candidate.ID == request.SelectedID {
			selectedPresent = true
			continue
		}
		if candidate.Status == 1 && candidate.LatencyMS > 0 {
			knownFast = append(knownFast, candidate)
		}
	}
	sort.Slice(knownFast, func(i, j int) bool {
		if knownFast[i].LatencyMS == knownFast[j].LatencyMS {
			return knownFast[i].ID < knownFast[j].ID
		}
		return knownFast[i].LatencyMS < knownFast[j].LatencyMS
	})
	if len(knownFast) > request.KnownFastLimit {
		knownFast = knownFast[:request.KnownFastLimit]
	}

	chosen := make(map[int64]struct{}, request.Limit)
	ids := make([]int64, 0, request.Limit)
	add := func(id int64) {
		if _, exists := chosen[id]; exists {
			return
		}
		chosen[id] = struct{}{}
		ids = append(ids, id)
	}
	if selectedPresent {
		add(request.SelectedID)
	}
	for _, candidate := range knownFast {
		add(candidate.ID)
	}

	unexplored := make([]int64, 0, len(request.Candidates)-len(ids))
	for _, candidate := range request.Candidates {
		if _, exists := chosen[candidate.ID]; !exists {
			unexplored = append(unexplored, candidate.ID)
		}
	}
	sort.Slice(unexplored, func(i, j int) bool { return unexplored[i] < unexplored[j] })
	fixedCount := len(ids)
	if len(unexplored) > 0 {
		index := floorMod(request.ExplorationOffset, len(unexplored))
		for len(ids) < request.Limit && len(ids) < len(request.Candidates) {
			add(unexplored[index])
			index = (index + 1) % len(unexplored)
		}
	}
	exploredCount := len(ids) - fixedCount
	nextOffset := 0
	if len(unexplored) > 0 {
		nextOffset = (floorMod(request.ExplorationOffset, len(unexplored)) + exploredCount) % len(unexplored)
	}
	return autoSwitchSelection{
		IDs:                   ids,
		ExploredCount:         exploredCount,
		ExplorationPoolSize:   len(unexplored),
		NextExplorationOffset: nextOffset,
	}, nil
}

func floorMod(value, divisor int) int {
	result := value % divisor
	if result < 0 {
		result += divisor
	}
	return result
}

func planSubscriptionUpdate(
	incoming []subscriptionIncoming,
	existing []subscriptionExisting,
) subscriptionPlan {
	unused := make(map[int64]subscriptionExisting, len(existing))
	byName := make(map[string]*orderedSubscriptionIDs, len(existing))
	byIdentity := make(map[string]*orderedSubscriptionIDs, len(existing))
	byIdentityAndName := make(map[subscriptionIdentityAndName]*orderedSubscriptionIDs, len(existing))

	push := func(index *orderedSubscriptionIDs, profile subscriptionExisting) {
		heap.Push(index, orderedSubscriptionID{UserOrder: profile.UserOrder, ID: profile.ID})
	}
	for _, profile := range existing {
		unused[profile.ID] = profile
		nameIndex := byName[profile.Name]
		if nameIndex == nil {
			nameIndex = &orderedSubscriptionIDs{}
			byName[profile.Name] = nameIndex
		}
		push(nameIndex, profile)

		identityIndex := byIdentity[profile.Identity]
		if identityIndex == nil {
			identityIndex = &orderedSubscriptionIDs{}
			byIdentity[profile.Identity] = identityIndex
		}
		push(identityIndex, profile)

		identityAndName := subscriptionIdentityAndName{Identity: profile.Identity, Name: profile.Name}
		combinedIndex := byIdentityAndName[identityAndName]
		if combinedIndex == nil {
			combinedIndex = &orderedSubscriptionIDs{}
			byIdentityAndName[identityAndName] = combinedIndex
		}
		push(combinedIndex, profile)
	}

	peekUnused := func(index *orderedSubscriptionIDs) (int64, bool) {
		for index != nil && index.Len() > 0 {
			candidate := (*index)[0]
			if _, available := unused[candidate.ID]; available {
				return candidate.ID, true
			}
			heap.Pop(index)
		}
		return 0, false
	}

	actions := make([]subscriptionAction, 0, len(incoming))
	for incomingIndex, profile := range incoming {
		userOrder := int64(incomingIndex + 1)
		identityAndName := subscriptionIdentityAndName{Identity: profile.Identity, Name: profile.Name}
		matchedID, matchedByEqual := peekUnused(byIdentityAndName[identityAndName])
		if !matchedByEqual {
			matchedID, matchedByEqual = peekUnused(byIdentity[profile.Identity])
		}
		matched := matchedByEqual
		if !matched {
			matchedID, matched = peekUnused(byName[profile.Name])
		}

		if !matched {
			actions = append(actions, subscriptionAction{
				IncomingIndex: incomingIndex,
				Action:        "add",
				UserOrder:     userOrder,
			})
			continue
		}

		persisted := unused[matchedID]
		delete(unused, matchedID)
		action := "unchanged"
		if !matchedByEqual || persisted.Name != profile.Name {
			action = "update"
		} else if persisted.UserOrder != userOrder {
			action = "reorder"
		}
		id := persisted.ID
		actions = append(actions, subscriptionAction{
			IncomingIndex: incomingIndex,
			ExistingID:    &id,
			Action:        action,
			UserOrder:     userOrder,
		})
	}

	deletions := make([]subscriptionExisting, 0, len(unused))
	for _, profile := range unused {
		deletions = append(deletions, profile)
	}
	sort.Slice(deletions, func(i, j int) bool {
		if deletions[i].UserOrder == deletions[j].UserOrder {
			return deletions[i].ID < deletions[j].ID
		}
		return deletions[i].UserOrder < deletions[j].UserOrder
	})
	deletionIDs := make([]int64, len(deletions))
	for index, profile := range deletions {
		deletionIDs[index] = profile.ID
	}
	return subscriptionPlan{Actions: actions, DeletionIDs: deletionIDs}
}
