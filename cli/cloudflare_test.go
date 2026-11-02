package main

import (
	"os"
	"regexp"
	"strings"
	"testing"
)

func TestTheRedirectIsTheAppsRedirect(t *testing.T) {
	src, err := os.ReadFile("../app/core/src/commonMain/kotlin/realm/Cloudflare.kt")
	if err != nil {
		t.Fatal(err)
	}
	consts := map[string]string{}
	for _, m := range regexp.MustCompile(`const val (\w+) = "?([^"\n]*)"?`).FindAllStringSubmatch(string(src), -1) {
		consts[m[1]] = m[2]
	}
	app := consts["REDIRECT"]
	for name, v := range consts {
		app = strings.ReplaceAll(app, "$"+name, v)
	}
	if app == "" || strings.Contains(app, "$") {
		t.Fatalf("can't read the app's REDIRECT from Cloudflare.kt: %q", app)
	}
	if cfRedirect != app {
		t.Errorf("cfRedirect = %q, the app's REDIRECT = %q", cfRedirect, app)
	}
}
