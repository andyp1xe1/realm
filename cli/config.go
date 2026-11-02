package main

import (
	"encoding/json"
	"os"
	"path/filepath"
)

type config struct {
	URL   string `json:"url"`
	Token string `json:"token,omitempty"`
	Realm string `json:"realm,omitempty"`

	Cloudflare *cfAuth `json:"cloudflare,omitempty"`
}

func configPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = filepath.Join(os.Getenv("HOME"), ".config")
	}
	return filepath.Join(dir, "realm", "config.json")
}

func readConfig() config {
	var cfg config
	if b, err := os.ReadFile(configPath()); err == nil {
		_ = json.Unmarshal(b, &cfg)
	}
	return cfg
}

func saveConfig(cfg config) error {
	p := configPath()
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return err
	}
	b, _ := json.MarshalIndent(cfg, "", "  ")
	f, err := os.CreateTemp(filepath.Dir(p), "config-*.json")
	if err != nil {
		return err
	}
	_, err = f.Write(b)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(f.Name(), p)
	}
	if err != nil {
		os.Remove(f.Name())
	}
	return err
}

func lockConfig() (unlock func()) {
	p := configPath() + ".lock"
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return func() {}
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return func() {}
	}
	if lockFile(f) != nil {
		f.Close()
		return func() {}
	}
	return func() { f.Close() }
}

func updateConfig(change func(*config)) error {
	defer lockConfig()()
	cfg := readConfig()
	change(&cfg)
	return saveConfig(cfg)
}
