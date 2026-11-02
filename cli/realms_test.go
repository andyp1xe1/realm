package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
)

type realmsWorker struct {
	mu   sync.Mutex
	seen []string
}

func (w *realmsWorker) paths() []string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return append([]string(nil), w.seen...)
}

func serveRealms(t *testing.T, realms ...Realm) *realmsWorker {
	t.Helper()
	tempConfig(t)
	w := &realmsWorker{}
	srv := httptest.NewServer(http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		w.mu.Lock()
		w.seen = append(w.seen, r.Method+" "+r.URL.Path)
		w.mu.Unlock()
		rw.Header().Set("Content-Type", "application/json")
		reply := func(v any) { _ = json.NewEncoder(rw).Encode(v) }
		info := Info{Version: workerVersion, Scope: "owner"}
		parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
		switch {
		case r.URL.Path == "/api/info":
			reply(info)
			return
		case r.URL.Path == "/api/realms":
			reply(map[string]any{"realms": realms})
			return
		case len(parts) == 4 && parts[0] == "api" && parts[1] == "realms":
			for _, re := range realms {
				if re.ID != parts[2] {
					continue
				}
				switch parts[3] {
				case "info":
					info.Realm = &re
					reply(info)
					return
				case "items":
					reply(map[string]any{"items": []Item{}})
					return
				case "links":
					reply(map[string]any{"links": []Link{}})
					return
				}
			}
		}
		rw.WriteHeader(404)
		fmt.Fprint(rw, `{"error":"not found"}`)
	}))
	t.Cleanup(srv.Close)
	if err := saveConfig(config{URL: srv.URL, Token: "t"}); err != nil {
		t.Fatal(err)
	}
	return w
}

func quietly(t *testing.T, f func() error) (string, error) {
	t.Helper()
	r, pw, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	out := os.Stdout
	os.Stdout = pw
	got := make(chan string)
	go func() {
		b, _ := io.ReadAll(r)
		got <- string(b)
	}()
	err = f()
	os.Stdout = out
	pw.Close()
	return <-got, err
}

func TestTheOnlyRealmIsUsedWithoutPickingIt(t *testing.T) {
	w := serveRealms(t, Realm{ID: "r1", Name: "One"})
	for _, cmd := range []string{"ls", "links"} {
		if _, err := quietly(t, func() error { return run("", cmd, nil) }); err != nil {
			t.Errorf("realm %s: %v", cmd, err)
		}
	}
	for _, want := range []string{"GET /api/realms/r1/items", "GET /api/realms/r1/links"} {
		if !contains(w.paths(), want) {
			t.Errorf("worker saw %v, want %s", w.paths(), want)
		}
	}
}

func TestSeveralRealmsAreNeverGuessed(t *testing.T) {
	w := serveRealms(t, Realm{ID: "r1", Name: "One"}, Realm{ID: "r2", Name: "Two"})
	if _, err := quietly(t, func() error { return run("", "ls", nil) }); err == nil {
		t.Error("realm ls with no realm picked: want an error")
	}
	out, err := quietly(t, func() error { return run("", "status", nil) })
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out, "One") {
		t.Errorf("status names a realm nobody picked:\n%s", out)
	}
	for _, p := range w.paths() {
		if strings.HasSuffix(p, "/items") || strings.HasSuffix(p, "/links") {
			t.Errorf("worker was asked %s", p)
		}
	}
}

func TestThePickedRealmIsUsed(t *testing.T) {
	w := serveRealms(t, Realm{ID: "r1", Name: "One"}, Realm{ID: "r2", Name: "Two"})
	if _, err := quietly(t, func() error { return run("", "use", []string{"Two"}) }); err != nil {
		t.Fatal(err)
	}
	out, err := quietly(t, func() error { return run("", "status", nil) })
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "Two") {
		t.Errorf("status doesn't name the picked realm:\n%s", out)
	}
	if _, err := quietly(t, func() error { return run("One", "ls", nil) }); err != nil {
		t.Errorf("realm -r One ls: %v", err)
	}
	for _, want := range []string{"GET /api/realms/r2/info", "GET /api/realms/r1/items"} {
		if !contains(w.paths(), want) {
			t.Errorf("worker saw %v, want %s", w.paths(), want)
		}
	}
}

func contains(xs []string, x string) bool {
	for _, y := range xs {
		if y == x {
			return true
		}
	}
	return false
}
