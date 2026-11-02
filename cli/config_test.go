package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func tempConfig(t *testing.T) {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("HOME", dir)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(dir, "config"))
	t.Setenv("REALM_URL", "")
	t.Setenv("REALM_TOKEN", "")
}

func fakeTokenEndpoint(t *testing.T) {
	t.Helper()
	var mu sync.Mutex
	used := map[string]bool{}
	n := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		refresh := r.FormValue("refresh_token")
		mu.Lock()
		spent := used[refresh]
		used[refresh] = true
		n++
		next := n
		mu.Unlock()
		time.Sleep(100 * time.Millisecond)
		w.Header().Set("Content-Type", "application/json")
		if spent {
			w.WriteHeader(400)
			fmt.Fprint(w, `{"error":"invalid_grant"}`)
			return
		}
		fmt.Fprintf(w, `{"access_token":"a%d","refresh_token":"r%d","expires_in":3600}`, next, next)
	}))
	t.Cleanup(srv.Close)
	old := cfTokenURL
	cfTokenURL = srv.URL
	t.Cleanup(func() { cfTokenURL = old })
}

func TestConcurrentRefreshesKeepTheLogin(t *testing.T) {
	tempConfig(t)
	fakeTokenEndpoint(t)
	expired := &cfAuth{Access: "a0", Refresh: "r0", Expires: 0, Account: "acc"}
	if err := saveConfig(config{URL: "https://realm.example", Cloudflare: expired}); err != nil {
		t.Fatal(err)
	}

	var wg sync.WaitGroup
	errs := make([]error, 2)
	for i := range errs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, errs[i] = cfAccess()
		}()
	}
	wg.Wait()

	for i, err := range errs {
		if err != nil {
			t.Errorf("refresh %d: %v", i, err)
		}
	}
	auth := readConfig().Cloudflare
	if auth == nil {
		t.Fatal("the login was dropped")
	}
	if auth.Account != "acc" {
		t.Errorf("account = %q, want acc", auth.Account)
	}
	if _, err := cfAccess(); err != nil {
		t.Errorf("after both refreshes: %v", err)
	}
}
