// realm — command line client for the realm dump.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
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
	base   string // where item routes go: https://x/api/realms/<id>, https://x/api, or https://…/s/<token>
	server string // the owner API, https://x/api; empty for share links
	token  string // a fixed owner token; empty for share links and Cloudflare logins
	viaCF  bool   // the owner is the saved Cloudflare login, renewed as it runs out
	http   *http.Client
}

// workerVersion is the worker this CLI is written against; an older one lacks things it uses.
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
		// A share link to one file downloads without being connected to anything.
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

// ---------- config ----------

type config struct {
	URL   string `json:"url"`             // the server's address, or a share link
	Token string `json:"token,omitempty"` // owner token; none for a share link
	Realm string `json:"realm,omitempty"` // id of the realm in use; empty = the first one

	Cloudflare *cfAuth `json:"cloudflare,omitempty"` // a Cloudflare login, for deploying and updating
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
	return os.WriteFile(p, b, 0o600)
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
		return errors.New("a server needs its owner token: realm login <url> <token>")
	}
	cfg := config{URL: strings.TrimSuffix(strings.TrimRight(args[0], "/"), "/api"), Token: args[1]}
	if !strings.HasPrefix(cfg.URL, "http") {
		cfg.URL = "https://" + cfg.URL
	}
	c := newClient(cfg)
	var info Info
	if err := c.do("GET", "/info", nil, &info); err != nil {
		if strings.Contains(err.Error(), "(404)") {
			return errors.New("that server is out of date: redeploy the worker")
		}
		return fmt.Errorf("can't sign in to %s: %w", cfg.URL, err)
	}
	if err := saveConfig(cfg); err != nil {
		return err
	}
	fmt.Println("signed in to", cfg.URL)
	warnOld(info)
	return c.status()
}

func connect(args []string) error {
	if len(args) != 1 || !isLink(args[0]) {
		return errors.New("usage: realm connect <link>   (a link with /s/ in it; for your own server, use realm login)")
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
	fmt.Println("connected")
	warnOld(info)
	return c.status()
}

func logout() error {
	cfRevoke(readConfig().Cloudflare)
	err := os.Remove(configPath())
	if errors.Is(err, os.ErrNotExist) {
		return errors.New("not signed in or connected")
	}
	if err == nil {
		fmt.Println("signed out")
	}
	return err
}

func warnOld(info Info) {
	if info.Version < workerVersion {
		fmt.Fprintf(os.Stderr, "note: this server runs worker version %d, older than this CLI expects (%d); redeploy it\n", info.Version, workerVersion)
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
		return nil, errors.New("not set up: run `realm login <url> <token>` or `realm connect <link>`")
	}
	c := newClient(cfg)
	if realm != "" {
		if c.server == "" {
			return nil, errors.New("a share link is one realm; -r only works signed in to your server")
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
	// No overall timeout (transfers can be huge), but don't hang forever on a dead server.
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.DialContext = (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext
	tr.TLSHandshakeTimeout = 15 * time.Second
	tr.ResponseHeaderTimeout = 2 * time.Minute
	c.http = &http.Client{Transport: tr}
	return c
}

// ---------- http ----------

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
		if json.Unmarshal(b, &e) == nil && e.Error != "" {
			return nil, fmt.Errorf("%s (%d)", e.Error, res.StatusCode)
		}
		return nil, fmt.Errorf("http %d", res.StatusCode)
	}
	return res, nil
}

// do sends an optional JSON body and decodes a JSON response into out (if non-nil).
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

// at is the same client aimed at another base, e.g. the owner API above any one realm.
func (c *Client) at(base string) *Client {
	cc := *c
	cc.base = base
	return &cc
}

// ---------- where you are ----------

func (c *Client) status() error {
	var info Info
	if err := c.do("GET", "/info", nil, &info); err != nil {
		return err
	}
	realm := info.Realm
	if c.server != "" {
		fmt.Println("server ", strings.TrimSuffix(c.server, "/api"))
		if realm == nil { // the plain /api routes use the first realm
			if rs, err := c.realmList(); err == nil && len(rs) > 0 {
				realm = &rs[0]
			}
		}
	} else {
		fmt.Println("link   ", c.base)
	}
	if realm != nil {
		fmt.Println("realm  ", realm.Name)
	}
	access := map[string]string{
		"owner": "everything", "full": "read, add, edit and delete", "read": "read only", "drop": "add only",
	}[info.Scope]
	if info.Items != nil {
		access += fmt.Sprintf(", %d picked item%s", *info.Items, plural(*info.Items))
		if info.Item != nil {
			access += ": " + describe(info.Item)
		}
	}
	fmt.Println("access ", access)
	var limits []string
	if info.UsesLeft != nil {
		limits = append(limits, fmt.Sprintf("%d use%s left", *info.UsesLeft, plural(int(*info.UsesLeft))))
	}
	if info.BytesLeft != nil {
		limits = append(limits, human(*info.BytesLeft)+" of uploads left")
	}
	if info.Expires != nil {
		limits = append(limits, "expires "+time.UnixMilli(*info.Expires).Format("Jan 02 15:04"))
	}
	if len(limits) > 0 {
		fmt.Println("limits ", strings.Join(limits, ", "))
	}
	fmt.Println("worker  version", info.Version)
	if readConfig().Cloudflare != nil {
		// Renews the login if its hour is up, so this also says whether it still works.
		if _, err := cfAccess(); err != nil {
			fmt.Println("cloudflare", err)
		} else {
			fmt.Println("cloudflare logged in, renews by itself")
		}
	}
	return nil
}

func plural(n int) string {
	if n == 1 {
		return ""
	}
	return "s"
}

// describe names an item in a line: its file name, or the start of its text.
func describe(it *Item) string {
	if it.Name != nil {
		return *it.Name
	}
	if it.Text != nil {
		return "“" + oneLine(*it.Text, 40) + "”"
	}
	return it.ID
}

// ---------- realms ----------

func (c *Client) realmList() ([]Realm, error) {
	var out struct{ Realms []Realm }
	return out.Realms, c.at(c.server).do("GET", "/realms", nil, &out)
}

// findRealm takes an id, a name, or the unique start of a name, in any case.
func (c *Client) findRealm(q string) (*Realm, error) {
	rs, err := c.realmList()
	if err != nil {
		return nil, err
	}
	lq := strings.ToLower(q)
	var starts []Realm
	for _, r := range rs {
		if r.ID == q || strings.ToLower(r.Name) == lq {
			return &r, nil
		}
		if strings.HasPrefix(strings.ToLower(r.Name), lq) {
			starts = append(starts, r)
		}
	}
	switch len(starts) {
	case 1:
		return &starts[0], nil
	case 0:
		return nil, fmt.Errorf("no realm called %q (see realm realms)", q)
	}
	var names []string
	for _, r := range starts {
		names = append(names, r.Name)
	}
	return nil, fmt.Errorf("%q could be %s", q, strings.Join(names, ", "))
}

// current is the realm the item commands go to: the chosen one, else the first.
func (c *Client) current(rs []Realm) string {
	if i := strings.Index(c.base, "/realms/"); i >= 0 {
		id, _ := url.PathUnescape(c.base[i+len("/realms/"):])
		return id
	}
	if len(rs) > 0 {
		return rs[0].ID
	}
	return ""
}

func (c *Client) realms(args []string) error {
	if c.server == "" {
		return errors.New("a share link is one realm; realms are for your own server")
	}
	sub := ""
	if len(args) > 0 {
		sub, args = args[0], args[1:]
	}
	srv := c.at(c.server)
	switch sub {
	case "":
		rs, err := c.realmList()
		if err != nil {
			return err
		}
		cur := c.current(rs)
		width := 0
		for _, r := range rs {
			width = max(width, len([]rune(r.Name)))
		}
		for _, r := range rs {
			mark := " "
			if r.ID == cur {
				mark = "*"
			}
			when := ""
			if r.Updated != nil {
				when = time.UnixMilli(*r.Updated).Format("Jan 02 15:04")
			}
			fmt.Printf("%s %-*s  %5d item%-1s  %s\n", mark, width, r.Name, r.Count, plural(int(r.Count)), when)
		}
		return nil
	case "add", "new":
		name := strings.Join(args, " ")
		if name == "" {
			return errors.New("usage: realm realms add <name>")
		}
		var r Realm
		if err := srv.do("POST", "/realms", map[string]string{"name": name}, &r); err != nil {
			return err
		}
		fmt.Printf("made “%s”; work in it with: realm use %s\n", r.Name, quoteArg(r.Name))
		return nil
	case "rename", "mv":
		if len(args) < 2 {
			return errors.New("usage: realm realms rename <realm> <new name>")
		}
		r, err := c.findRealm(args[0])
		if err != nil {
			return err
		}
		return srv.do("PATCH", "/realms/"+url.PathEscape(r.ID), map[string]string{"name": strings.Join(args[1:], " ")}, nil)
	case "rm", "del", "delete":
		fs := flag.NewFlagSet("realms rm", flag.ExitOnError)
		yes := fs.Bool("y", false, "don't ask")
		parse(fs, args)
		if fs.NArg() != 1 {
			return errors.New("usage: realm realms rm <realm> [-y]")
		}
		r, err := c.findRealm(fs.Arg(0))
		if err != nil {
			return err
		}
		if err := confirm(fmt.Sprintf("Delete “%s” and its %d item%s? Its links stop working too.", r.Name, r.Count, plural(int(r.Count))), *yes); err != nil {
			return err
		}
		if err := srv.do("DELETE", "/realms/"+url.PathEscape(r.ID), nil, nil); err != nil {
			return err
		}
		if cfg := readConfig(); cfg.Realm == r.ID { // it was the one in use: back to the first
			cfg.Realm = ""
			_ = saveConfig(cfg)
		}
		fmt.Printf("deleted “%s”\n", r.Name)
		return nil
	}
	return fmt.Errorf("unknown: realm realms %s (add, rename, rm)", sub)
}

func (c *Client) use(args []string) error {
	if c.server == "" {
		return errors.New("a share link is one realm; realm use is for your own server")
	}
	if len(args) == 0 {
		return errors.New("usage: realm use <realm>")
	}
	r, err := c.findRealm(strings.Join(args, " "))
	if err != nil {
		return err
	}
	cfg := readConfig()
	cfg.Realm = r.ID
	if err := saveConfig(cfg); err != nil {
		return err
	}
	fmt.Printf("using “%s”\n", r.Name)
	return nil
}

func quoteArg(s string) string {
	if strings.ContainsAny(s, " '\"$`\\") {
		return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
	}
	return s
}

// confirm asks on the terminal; without one (scripts), only -y goes ahead.
func confirm(question string, yes bool) error {
	if yes {
		return nil
	}
	if !isTerminal(os.Stdin) {
		return errors.New("this asks before deleting; add -y to go ahead")
	}
	fmt.Fprint(os.Stderr, question, " [y/N] ")
	var answer string
	fmt.Scanln(&answer)
	if a := strings.ToLower(strings.TrimSpace(answer)); a == "y" || a == "yes" {
		return nil
	}
	return errors.New("left as it was")
}

// ---------- commands ----------

func (c *Client) add(args []string) error {
	fs := flag.NewFlagSet("add", flag.ExitOnError)
	caption := fs.String("t", "", "caption for files")
	asNote := fs.Bool("n", false, "treat arguments as text even if they name files")
	parse(fs, args)
	args = fs.Args()

	if len(args) == 0 || (len(args) == 1 && args[0] == "-") {
		b, err := io.ReadAll(os.Stdin)
		if err != nil {
			return err
		}
		if len(bytes.TrimSpace(b)) == 0 {
			return errors.New("nothing to add")
		}
		return c.note(string(b))
	}

	var files []string
	if !*asNote {
		for _, a := range args {
			if st, err := os.Stat(a); err == nil && st.Mode().IsRegular() {
				files = append(files, a)
			}
		}
	}
	if len(files) == 0 {
		return c.note(strings.Join(args, " "))
	}
	if len(files) != len(args) {
		return errors.New("mixing files and text; use -t for a caption or -n to force a note")
	}
	for _, f := range files {
		it, err := c.upload(f, *caption)
		if err != nil {
			return fmt.Errorf("%s: %w", f, err)
		}
		fmt.Println(it.ID, *it.Name, human(*it.Size))
	}
	return nil
}

func (c *Client) note(text string) error {
	res, err := c.req("POST", "/items", strings.NewReader(text), int64(len(text)), "text/plain; charset=utf-8")
	if err != nil {
		return err
	}
	defer res.Body.Close()
	var it Item
	if err := json.NewDecoder(res.Body).Decode(&it); err != nil {
		return err
	}
	fmt.Println(it.ID)
	return nil
}

func (c *Client) upload(path, caption string) (*Item, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	name := filepath.Base(path)
	ctype := mime.TypeByExtension(filepath.Ext(name))
	var it Item

	if st.Size() <= singleMax {
		q := ""
		if caption != "" {
			q = "?text=" + url.QueryEscape(caption)
		}
		res, err := c.req("PUT", "/files/"+url.PathEscape(name)+q, progress(f, st.Size(), name), st.Size(), ctype)
		if err != nil {
			return nil, err
		}
		defer res.Body.Close()
		return &it, json.NewDecoder(res.Body).Decode(&it)
	}

	var up struct{ Upload string }
	if err := c.do("POST", "/uploads", map[string]string{"name": name, "type": ctype, "text": caption}, &up); err != nil {
		return nil, err
	}
	type part struct {
		Part int    `json:"part"`
		Etag string `json:"etag"`
	}
	var parts []part
	pr := progress(f, st.Size(), name)
	for n, off := 1, int64(0); off < st.Size(); n, off = n+1, off+partSize {
		size := min(partSize, st.Size()-off)
		var p part
		res, err := c.req("PUT", fmt.Sprintf("/uploads/%s/%d", up.Upload, n), io.LimitReader(pr, size), size, "")
		if err == nil {
			err = json.NewDecoder(res.Body).Decode(&p)
			res.Body.Close()
		}
		if err != nil {
			_ = c.do("DELETE", "/uploads/"+up.Upload, nil, nil)
			return nil, err
		}
		parts = append(parts, p)
	}
	return &it, c.do("POST", "/uploads/"+up.Upload+"/complete", map[string]any{"parts": parts}, &it)
}

func (c *Client) ls(args []string) error {
	fs := flag.NewFlagSet("ls", flag.ExitOnError)
	kind := fs.String("k", "", "kinds: note,link,image,audio,video,file")
	n := fs.Int("n", 30, "how many")
	asJSON := fs.Bool("json", false, "raw JSON")
	before := fs.String("before", "", "page: items older than this id")
	parse(fs, args)
	q := url.Values{}
	q.Set("limit", strconv.Itoa(*n))
	if s := strings.Join(fs.Args(), " "); s != "" {
		q.Set("q", s)
	}
	if *kind != "" {
		q.Set("kind", *kind)
	}
	if *before != "" {
		q.Set("before", *before)
	}
	if *asJSON {
		return c.stream("GET", "/items?"+q.Encode(), nil, os.Stdout)
	}
	var out struct {
		Items []Item
		Next  *string
	}
	if err := c.do("GET", "/items?"+q.Encode(), nil, &out); err != nil {
		return err
	}
	for i := len(out.Items) - 1; i >= 0; i-- { // oldest first, newest lands next to the prompt
		it := out.Items[i]
		t := time.UnixMilli(it.Created).Format("Jan 02 15:04")
		desc := ""
		if it.Name != nil {
			desc = *it.Name + "  " + human(*it.Size)
			if it.Text != nil {
				desc += "  — " + oneLine(*it.Text, 50)
			}
		} else if it.Text != nil {
			desc = oneLine(*it.Text, 90)
		}
		fmt.Printf("%s  %s  %-5s  %s\n", it.ID, t, it.Kind, desc)
	}
	if out.Next != nil {
		fmt.Fprintf(os.Stderr, "… more: realm ls --before %s\n", *out.Next)
	}
	return nil
}

func (c *Client) resolve(id string) (*Item, error) {
	if id == "last" {
		var out struct{ Items []Item }
		if err := c.do("GET", "/items?limit=1", nil, &out); err != nil {
			return nil, err
		}
		if len(out.Items) == 0 {
			return nil, errors.New("this realm is empty")
		}
		return &out.Items[0], nil
	}
	var it Item
	return &it, c.do("GET", "/items/"+url.PathEscape(id), nil, &it)
}

func (c *Client) get(args []string) error {
	fs := flag.NewFlagSet("get", flag.ExitOnError)
	out := fs.String("o", "", "output file or directory, - for stdout")
	parse(fs, args)
	if fs.NArg() == 0 {
		return errors.New("usage: realm get <id|last>... [-o path]")
	}
	for _, id := range fs.Args() {
		it, err := c.resolve(id)
		if err != nil {
			return fmt.Errorf("%s: %w", id, err)
		}
		if *out == "-" {
			if err := c.stream("GET", "/items/"+it.ID+"/raw", nil, os.Stdout); err != nil {
				return err
			}
			continue
		}
		dest := destFor(it, *out)
		if err := c.download(it, dest); err != nil {
			return err
		}
		fmt.Println(dest)
	}
	return nil
}

// getLink downloads what a share link to one file shares, without connecting to it. Costs one use.
func getLink(args []string) error {
	fs := flag.NewFlagSet("get", flag.ExitOnError)
	out := fs.String("o", "", "output file or directory, - for stdout")
	parse(fs, args)
	for _, link := range fs.Args() {
		if !isLink(link) {
			return fmt.Errorf("%s: mix of links and ids; get ids after realm login or connect", link)
		}
		c := newClient(config{URL: link})
		var info Info
		if err := c.do("GET", "/info", nil, &info); err != nil {
			return fmt.Errorf("%s: %w", link, err)
		}
		if info.Item == nil {
			return fmt.Errorf("%s shares more than one file; realm connect it to look around", link)
		}
		if *out == "-" {
			if err := c.stream("GET", "/items/"+info.Item.ID+"/raw", nil, os.Stdout); err != nil {
				return err
			}
			continue
		}
		dest := destFor(info.Item, *out)
		if err := c.download(info.Item, dest); err != nil {
			return err
		}
		fmt.Println(dest)
	}
	return nil
}

// destFor is where get saves an item: its own name, in -o's directory or as -o.
func destFor(it *Item, out string) string {
	name := it.ID + ".txt"
	if it.Name != nil {
		name = *it.Name
	}
	if out == "" {
		return name
	}
	if st, err := os.Stat(out); err == nil && st.IsDir() {
		return filepath.Join(out, name)
	}
	return out
}

func (c *Client) download(it *Item, dest string) error {
	res, err := c.req("GET", "/items/"+it.ID+"/raw", nil, -1, "")
	if err != nil {
		return err
	}
	defer res.Body.Close()
	tmp := dest + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	_, err = io.Copy(f, progress(res.Body, res.ContentLength, filepath.Base(dest)))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, dest)
}

func (c *Client) cat(args []string) error {
	if len(args) != 1 {
		return errors.New("usage: realm cat <id|last>")
	}
	it, err := c.resolve(args[0])
	if err != nil {
		return err
	}
	return c.stream("GET", "/items/"+it.ID+"/raw", nil, os.Stdout)
}

func (c *Client) edit(args []string) error {
	if len(args) < 2 {
		return errors.New("usage: realm edit <id> <text...>")
	}
	it, err := c.resolve(args[0])
	if err != nil {
		return err
	}
	return c.do("PATCH", "/items/"+it.ID, map[string]string{"text": strings.Join(args[1:], " ")}, nil)
}

func (c *Client) rm(args []string) error {
	fs := flag.NewFlagSet("rm", flag.ExitOnError)
	yes := fs.Bool("y", false, "don't ask")
	parse(fs, args)
	if fs.NArg() == 0 {
		return errors.New("usage: realm rm <id>... [-y]")
	}
	var items []*Item
	for _, id := range fs.Args() {
		it, err := c.resolve(id)
		if err != nil {
			return fmt.Errorf("%s: %w", id, err)
		}
		items = append(items, it)
	}
	q := fmt.Sprintf("Delete %d items? Links that share only them stop working.", len(items))
	if len(items) == 1 {
		q = "Delete " + describe(items[0]) + "? Links that share only it stop working."
	}
	if err := confirm(q, *yes); err != nil {
		return err
	}
	for _, it := range items {
		if err := c.do("DELETE", "/items/"+it.ID, nil, nil); err != nil {
			return fmt.Errorf("%s: %w", it.ID, err)
		}
	}
	return nil
}

func (c *Client) link(args []string) error {
	fs := flag.NewFlagSet("link", flag.ExitOnError)
	ttl := fs.String("ttl", "", "lifetime: 30m, 12h, 7d (default: forever)")
	uses := fs.Int("uses", 0, "max uses (default: unlimited)")
	label := fs.String("label", "", "who it's for; shows up as the source of dropped items")
	maxSize := fs.String("max-size", "", "upload budget for --drop/--full links, e.g. 500MB, 2GB")
	drop := fs.Bool("drop", false, "upload-only link to the whole realm")
	read := fs.Bool("read", false, "read-only link to the whole realm")
	full := fs.Bool("full", false, "read+write link to the whole realm")
	parse(fs, args)

	body := map[string]any{}
	switch {
	case *drop:
		body["scope"] = "drop"
	case *read:
		body["scope"] = "read"
	case *full:
		body["scope"] = "full"
	case fs.NArg() > 0:
		var ids []string
		for _, a := range fs.Args() {
			it, err := c.resolve(a)
			if err != nil {
				return err
			}
			ids = append(ids, it.ID)
		}
		body["items"] = ids
	default:
		return errors.New("usage: realm link <id>... | --drop | --read | --full")
	}
	if *ttl != "" {
		d, err := parseDur(*ttl)
		if err != nil {
			return err
		}
		body["ttl"] = int64(d.Seconds())
	}
	if *uses > 0 {
		body["uses"] = *uses
	}
	if *label != "" {
		body["label"] = *label
	}
	if *maxSize != "" {
		n, err := parseSize(*maxSize)
		if err != nil {
			return err
		}
		body["max_bytes"] = n
	}
	var l Link
	if err := c.do("POST", "/links", body, &l); err != nil {
		return err
	}
	fmt.Println(l.URL)
	return nil
}

func (c *Client) links() error {
	var out struct{ Links []Link }
	if err := c.do("GET", "/links", nil, &out); err != nil {
		return err
	}
	for _, l := range out.Links {
		exp := "never"
		if l.Expires != nil {
			exp = time.UnixMilli(*l.Expires).Format("Jan 02 15:04")
		}
		uses := strconv.FormatInt(l.Uses, 10)
		if l.MaxUses != nil {
			uses += "/" + strconv.FormatInt(*l.MaxUses, 10)
		}
		what := l.Scope
		if l.Items != nil {
			what += ":" + strings.Join(l.Items, ",")
		}
		if l.Label != nil {
			what += " (" + *l.Label + ")"
		}
		fmt.Printf("%s  expires %-12s  uses %-6s  %s\n", l.URL, exp, uses, what)
	}
	return nil
}

func (c *Client) revoke(args []string) error {
	fs := flag.NewFlagSet("revoke", flag.ExitOnError)
	yes := fs.Bool("y", false, "don't ask")
	parse(fs, args)
	if fs.NArg() == 0 {
		return errors.New("usage: realm revoke <token|url>... [-y]")
	}
	q := "Revoke this link? Anyone using it loses access straight away."
	if fs.NArg() > 1 {
		q = fmt.Sprintf("Revoke %d links? Anyone using them loses access straight away.", fs.NArg())
	}
	if err := confirm(q, *yes); err != nil {
		return err
	}
	for _, t := range fs.Args() {
		t = t[strings.LastIndex(t, "/")+1:]
		if err := c.do("DELETE", "/links/"+url.PathEscape(t), nil, nil); err != nil {
			return err
		}
	}
	return nil
}

// ---------- helpers ----------

// parse lets flags appear anywhere, e.g. `realm get last -o out`.
func parse(fs *flag.FlagSet, args []string) {
	var flags, pos []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		if a == "--" {
			pos = append(pos, args[i+1:]...)
			break
		}
		if !strings.HasPrefix(a, "-") || a == "-" {
			pos = append(pos, a)
			continue
		}
		flags = append(flags, a)
		name := strings.TrimLeft(a, "-")
		if f := fs.Lookup(name); f != nil && !strings.Contains(name, "=") && i+1 < len(args) {
			if b, ok := f.Value.(interface{ IsBoolFlag() bool }); !ok || !b.IsBoolFlag() {
				flags = append(flags, args[i+1])
				i++
			}
		}
	}
	fs.Parse(append(flags, append([]string{"--"}, pos...)...))
}

func parseDur(s string) (time.Duration, error) {
	if strings.HasSuffix(s, "d") {
		n, err := strconv.ParseFloat(strings.TrimSuffix(s, "d"), 64)
		return time.Duration(n * 24 * float64(time.Hour)), err
	}
	if strings.HasSuffix(s, "w") {
		n, err := strconv.ParseFloat(strings.TrimSuffix(s, "w"), 64)
		return time.Duration(n * 7 * 24 * float64(time.Hour)), err
	}
	return time.ParseDuration(s)
}

func parseSize(s string) (int64, error) {
	u := strings.ToUpper(strings.TrimSpace(s))
	mult := int64(1)
	for _, x := range []struct {
		suffix string
		m      int64
	}{{"TB", 1 << 40}, {"GB", 1 << 30}, {"MB", 1 << 20}, {"KB", 1 << 10}, {"B", 1}} {
		if strings.HasSuffix(u, x.suffix) {
			u, mult = strings.TrimSpace(strings.TrimSuffix(u, x.suffix)), x.m
			break
		}
	}
	f, err := strconv.ParseFloat(u, 64)
	if err != nil || f <= 0 {
		return 0, fmt.Errorf("bad size %q (try 500MB)", s)
	}
	return int64(f * float64(mult)), nil
}

func human(n int64) string {
	f := float64(n)
	for _, u := range []string{"B", "KB", "MB", "GB"} {
		if f < 1024 || u == "GB" {
			if u == "B" {
				return fmt.Sprintf("%d B", n)
			}
			return fmt.Sprintf("%.1f %s", f, u)
		}
		f /= 1024
	}
	return ""
}

func oneLine(s string, n int) string {
	s = strings.Join(strings.Fields(s), " ")
	if r := []rune(s); len(r) > n {
		return string(r[:n-1]) + "…"
	}
	return s
}

type progressReader struct {
	r           io.Reader
	total, done int64
	name        string
	last        time.Time
	finished    bool
}

// progress reports transfer progress on stderr for big transfers when stderr is a terminal.
func progress(r io.Reader, total int64, name string) io.Reader {
	if total < 4<<20 || !isTerminal(os.Stderr) {
		return r
	}
	return &progressReader{r: r, total: total, name: name}
}

func (p *progressReader) Read(b []byte) (int, error) {
	n, err := p.r.Read(b)
	p.done += int64(n)
	end := err == io.EOF || p.done >= p.total
	if !p.finished && (end || time.Since(p.last) > 200*time.Millisecond) {
		p.last = time.Now()
		fmt.Fprintf(os.Stderr, "\r%s  %s / %s  %3d%%", p.name, human(p.done), human(p.total), p.done*100/p.total)
		if end {
			p.finished = true
			fmt.Fprintln(os.Stderr)
		}
	}
	return n, err
}

func isTerminal(f *os.File) bool {
	st, err := f.Stat()
	if err != nil || st.Mode()&os.ModeCharDevice == 0 {
		return false
	}
	// /dev/null is a character device too, but nobody is there to answer.
	null, err := os.Stat(os.DevNull)
	return err != nil || !os.SameFile(st, null)
}
