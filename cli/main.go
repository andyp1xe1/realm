// realm — command line client for the realm dump.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

const usage = `realm — dump notes and files from anywhere

  realm login                     log in with Cloudflare; finds your realm, no token needed
  realm login <url> <token>       sign in to your own server with its owner token
  realm connect <link>            use a share link someone sent you
  realm logout                    forget the saved server or link
  realm status                    where you're connected, which realm, what you can do

  realm realms                    list your realms (* = the one in use)
  realm realms add <name>         make a realm
  realm realms rename <realm> <name>
  realm realms rm <realm>         delete a realm and everything in it   (-y)
  realm use <realm>               work in this realm from now on

  realm add <text...|file...|->   dump a note, files, or stdin   (-t caption)
  realm ls [query...]             list / search   (-k image,file  -n 20  --json)
  realm get <id|last|link>...     download into cwd   (-o path, -o - for stdout)
  realm cat <id|last>             print a note or file to stdout
  realm edit <id> <text...>       replace a note's text / a file's caption
  realm rm <id>...                delete   (-y)
  realm link <id>...              link to just these items     (--ttl 1d  --uses 3)
  realm link --drop|--read|--full link to the whole realm   (--ttl --label --max-size)
  realm links                     list this realm's active links
  realm revoke <token|url>...     kill links   (-y)
  realm help-agent                print the API cheat-sheet for this realm or link

A <realm> is its name (or the start of it) or its id. -r <realm> before a command uses
another realm for that one command: realm -r trip ls

env: REALM_URL, REALM_TOKEN, REALM_REALM override the saved config.
`

type Item struct {
	ID      string  `json:"id"`
	Kind    string  `json:"kind"`
	Text    *string `json:"text"`
	Name    *string `json:"name"`
	Mime    *string `json:"mime"`
	Size    *int64  `json:"size"`
	Src     *string `json:"src"`
	Created int64   `json:"created"`
}

type Realm struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Created int64  `json:"created"`
	Count   int64  `json:"count"`
	Updated *int64 `json:"updated"`
}

// Info is what a server or link says about itself; asking never spends a link's use.
type Info struct {
	Fork      string  `json:"fork"` // GitHub owner/repo of a fork; empty for realm itself
	Version   int     `json:"version"`
	Scope     string  `json:"scope"`
	Realm     *Realm  `json:"realm"`
	Label     *string `json:"label"`
	Expires   *int64  `json:"expires"`
	UsesLeft  *int64  `json:"uses_left"`
	BytesLeft *int64  `json:"bytes_left"`
	Items     *int    `json:"items"`
	Item      *Item   `json:"item"`
}

type Link struct {
	Token   string   `json:"token"`
	Scope   string   `json:"scope"`
	Items   []string `json:"items"`
	Label   *string  `json:"label"`
	Expires *int64   `json:"expires"`
	MaxUses *int64   `json:"max_uses"`
	Uses    int64    `json:"uses"`
	URL     string   `json:"url"`
}

type Client struct {
	base   string
	server string
	token  string
	viaCF  bool
	http   *http.Client
}

const workerFork = ""
const workerVersion = 3

const partSize = 32 << 20
const singleMax = 90 << 20

func main() {
	args := os.Args[1:]
	realm := os.Getenv("REALM_REALM")
	if len(args) >= 2 && (args[0] == "-r" || args[0] == "--realm") {
		realm, args = args[1], args[2:]
	}
	if len(args) < 1 {
		fmt.Print(usage)
		os.Exit(2)
	}
	cmd, args := args[0], args[1:]
	switch cmd {
	case "-h", "--help", "help":
		fmt.Print(usage)
		return
	case "login":
		check(login(args))
		return
	case "connect":
		check(connect(args))
		return
	case "logout":
		check(logout())
		return
	case "get":
		if len(args) > 0 && isLink(args[0]) {
			check(getLink(args))
			return
		}
	}
	c, err := loadClient(realm)
	check(err)
	switch cmd {
	case "status", "whoami":
		err = c.status()
	case "realms":
		err = c.realms(args)
	case "use":
		err = c.use(args)
	case "add", "a":
		err = c.add(args)
	case "ls", "list", "find", "search":
		err = c.ls(args)
	case "get":
		err = c.get(args)
	case "cat":
		err = c.cat(args)
	case "edit":
		err = c.edit(args)
	case "rm", "del":
		err = c.rm(args)
	case "link", "share":
		err = c.link(args)
	case "links":
		err = c.links()
	case "revoke":
		err = c.revoke(args)
	case "help-agent":
		err = c.stream("GET", "", nil, os.Stdout)
	default:
		err = fmt.Errorf("unknown command %q (try realm help)", cmd)
	}
	check(err)
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "realm:", err)
		os.Exit(1)
	}
}

func isLink(s string) bool {
	return strings.HasPrefix(s, "http") && strings.Contains(s, "/s/")
}

func login(args []string) error {
	if len(args) == 0 || args[0] == "--cloudflare" {
		return loginCloudflare()
	}
	if isLink(args[0]) {
		return errors.New("that's a share link: use `realm connect <link>`")
	}
	if len(args) < 2 {
		return errors.New("usage: realm login [<url> <token>]")
	}
	cfg := config{URL: strings.TrimSuffix(strings.TrimRight(args[0], "/"), "/api"), Token: args[1]}
	if !strings.HasPrefix(cfg.URL, "http") {
		cfg.URL = "https://" + cfg.URL
	}
	c := newClient(cfg)
	var info Info
	if err := c.do("GET", "/info", nil, &info); err != nil {
		if hasStatus(err, http.StatusNotFound) {
			return errors.New("worker too old: redeploy it")
		}
		return fmt.Errorf("can't sign in to %s: %w", cfg.URL, err)
	}
	if err := saveConfig(cfg); err != nil {
		return err
	}
	warnMismatch(info)
	return c.status()
}

func connect(args []string) error {
	if len(args) != 1 || !isLink(args[0]) {
		return errors.New("usage: realm connect <link>")
	}
	cfg := config{URL: strings.TrimRight(args[0], "/")}
	c := newClient(cfg)
	var info Info
	if err := c.do("GET", "/info", nil, &info); err != nil {
		return fmt.Errorf("can't use that link: %w", err)
	}
	if err := saveConfig(cfg); err != nil {
		return err
	}
	warnMismatch(info)
	return c.status()
}

func logout() error {
	cfRevoke(readConfig().Cloudflare)
	err := os.Remove(configPath())
	if errors.Is(err, os.ErrNotExist) {
		return errors.New("not logged in")
	}
	if err == nil {
		fmt.Println("signed out")
	}
	return err
}

func warnMismatch(info Info) {
	switch {
	case info.Fork != workerFork:
		fork := info.Fork
		if fork == "" {
			fork = "realm"
		}
		fmt.Fprintf(os.Stderr, "server runs %s v%d, not the worker this CLI is for: some things may not work\n", fork, info.Version)
	case info.Version < workerVersion:
		fmt.Fprintf(os.Stderr, "worker v%d is older than this CLI (v%d): redeploy it\n", info.Version, workerVersion)
	case info.Version > workerVersion:
		fmt.Fprintf(os.Stderr, "worker v%d is newer than this CLI (v%d): update the CLI\n", info.Version, workerVersion)
	}
}

func loadClient(realm string) (*Client, error) {
	cfg := readConfig()
	if v := os.Getenv("REALM_URL"); v != "" {
		cfg = config{URL: v}
	}
	if v := os.Getenv("REALM_TOKEN"); v != "" {
		cfg.Token = v
	}
	if cfg.URL == "" {
		return nil, errors.New("not logged in: realm login, or realm connect <link>")
	}
	c := newClient(cfg)
	if realm != "" {
		if c.server == "" {
			return nil, errors.New("-r needs realm login, not a link")
		}
		r, err := c.findRealm(realm)
		if err != nil {
			return nil, err
		}
		c.base = c.server + "/realms/" + url.PathEscape(r.ID)
	}
	return c, nil
}

func newClient(cfg config) *Client {
	c := &Client{token: cfg.Token, viaCF: cfg.Token == "" && cfg.Cloudflare != nil && !isLink(cfg.URL)}
	if isLink(cfg.URL) {
		c.base = strings.TrimRight(cfg.URL, "/")
	} else {
		c.server = strings.TrimSuffix(strings.TrimRight(cfg.URL, "/"), "/api") + "/api"
		c.base = c.server
		if cfg.Realm != "" {
			c.base += "/realms/" + url.PathEscape(cfg.Realm)
		}
	}
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.DialContext = (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext
	tr.TLSHandshakeTimeout = 15 * time.Second
	tr.ResponseHeaderTimeout = 2 * time.Minute
	c.http = &http.Client{Transport: tr}
	return c
}

func (c *Client) req(method, path string, body io.Reader, size int64, ctype string) (*http.Response, error) {
	r, err := http.NewRequest(method, c.base+path, body)
	if err != nil {
		return nil, err
	}
	if body != nil && size >= 0 {
		r.ContentLength = size
	}
	if ctype != "" {
		r.Header.Set("Content-Type", ctype)
	}
	if c.token != "" {
		r.Header.Set("Authorization", "Bearer "+c.token)
	} else if c.viaCF {
		t, err := cfAccess()
		if err != nil {
			return nil, err
		}
		r.Header.Set("Authorization", "Bearer "+t)
	}
	r.Header.Set("X-Realm-Source", "cli")
	res, err := c.http.Do(r)
	if err != nil {
		return nil, err
	}
	if res.StatusCode >= 400 {
		defer res.Body.Close()
		var e struct{ Error string }
		b, _ := io.ReadAll(res.Body)
		_ = json.Unmarshal(b, &e)
		return nil, &httpError{Status: res.StatusCode, Message: e.Error}
	}
	return res, nil
}

type httpError struct {
	Status  int
	Message string
}

func (e *httpError) Error() string {
	if e.Message == "" {
		return fmt.Sprintf("http %d", e.Status)
	}
	return fmt.Sprintf("%s (%d)", e.Message, e.Status)
}

func hasStatus(err error, status int) bool {
	var he *httpError
	return errors.As(err, &he) && he.Status == status
}

func (c *Client) do(method, path string, in, out any) error {
	var body io.Reader
	var size int64 = -1
	ctype := ""
	if in != nil {
		b, _ := json.Marshal(in)
		body, size, ctype = bytes.NewReader(b), int64(len(b)), "application/json"
	}
	res, err := c.req(method, path, body, size, ctype)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if out == nil {
		_, err = io.Copy(io.Discard, res.Body)
		return err
	}
	return json.NewDecoder(res.Body).Decode(out)
}

func (c *Client) stream(method, path string, body io.Reader, w io.Writer) error {
	res, err := c.req(method, path, body, -1, "")
	if err != nil {
		return err
	}
	defer res.Body.Close()
	_, err = io.Copy(w, res.Body)
	return err
}

func (c *Client) at(base string) *Client {
	cc := *c
	cc.base = base
	return &cc
}
