package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

type configDirCase struct {
	OS  string            `json:"os"`
	Env map[string]string `json:"env"`
	Dir string            `json:"dir"`
}

func TestConfigDirMatchesTheDesktopApp(t *testing.T) {
	b, err := os.ReadFile(filepath.Join("testdata", "config-dirs.json"))
	if err != nil {
		t.Fatal(err)
	}
	var cases []configDirCase
	if err := json.Unmarshal(b, &cases); err != nil {
		t.Fatal(err)
	}
	ran := 0
	for _, c := range cases {
		if c.OS != runtime.GOOS {
			continue
		}
		ran++
		for _, k := range []string{"HOME", "XDG_CONFIG_HOME", "APPDATA"} {
			t.Setenv(k, c.Env[k])
		}
		if got, want := configPath(), filepath.Join(c.Dir, "config.json"); got != want {
			t.Errorf("env %v: config at %s, want %s", c.Env, got, want)
		}
	}
	if ran == 0 {
		t.Skipf("no cases for %s", runtime.GOOS)
	}
}
