package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func fakeWorker(t *testing.T, refusals int64) (*Client, *atomic.Int64) {
	t.Helper()
	var asked atomic.Int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/info" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if asked.Add(1) <= refusals {
			w.WriteHeader(401)
			fmt.Fprint(w, `{"error":"bad token"}`)
			return
		}
		fmt.Fprintf(w, `{"fork":"","version":%d,"scope":"owner"}`, workerVersion)
	}))
	t.Cleanup(srv.Close)
	old := ownerPoll
	ownerPoll = time.Millisecond
	t.Cleanup(func() { ownerPoll = old })
	return newClient(config{URL: srv.URL, Token: "t"}), &asked
}

func TestRefusalOfAnAlreadyClaimedWorkerIsFinal(t *testing.T) {
	c, asked := fakeWorker(t, 1000)
	_, err := ownerInfo(c, false)
	if !hasStatus(err, http.StatusUnauthorized) {
		t.Fatalf("err = %v, want the worker's 401", err)
	}
	if n := asked.Load(); n != 1 {
		t.Errorf("asked %d times, want once", n)
	}
}

func TestJustClaimedWorkerIsAskedUntilItAgrees(t *testing.T) {
	c, asked := fakeWorker(t, 2)
	info, err := ownerInfo(c, true)
	if err != nil {
		t.Fatal(err)
	}
	if info.Scope != "owner" || asked.Load() != 3 {
		t.Errorf("scope %q after %d asks, want owner after 3", info.Scope, asked.Load())
	}
}
