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

  realm login <url> [token]       save server (url may also be a share link)
  realm add <text...|file...|->   dump a note, files, or stdin   (-t caption)
  realm ls [query...]             list / search   (-k image,file  -n 20  --json)
  realm get <id|last>...          download into cwd   (-o path, -o - for stdout)
  realm cat <id|last>             print a note or file to stdout
  realm edit <id> <text...>       replace a note's text / a file's caption
  realm rm <id>...                delete
  realm link <id>                 download link for one item   (--ttl 1d  --uses 3)
  realm link --drop|--read|--full share link for the whole dump (--ttl --label --max-size)
  realm links                     list active links
  realm revoke <token>            kill a link
  realm help-agent                print the API cheat-sheet for this server/link

env: REALM_URL, REALM_TOKEN override the saved config.
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

type Link struct {
	Token   string  `json:"token"`
	Scope   string  `json:"scope"`
	Item    *string `json:"item"`
	Label   *string `json:"label"`
	Expires *int64  `json:"expires"`
	MaxUses *int64  `json:"max_uses"`
	Uses    int64   `json:"uses"`
	URL     string  `json:"url"`
}

type Client struct {
	base  string // e.g. https://realm.x.workers.dev/api or https://…/s/<token>
	token string // bearer, empty for share links
	http  *http.Client
}

const partSize = 32 << 20
const singleMax = 90 << 20

func main() {
	if len(os.Args) < 2 {
		fmt.Print(usage)
		os.Exit(2)
	}
	cmd, args := os.Args[1], os.Args[2:]
	if cmd == "-h" || cmd == "--help" || cmd == "help" {
		fmt.Print(usage)
		return
	}
	if cmd == "login" {
		check(login(args))
		return
	}
	c, err := loadClient()
	check(err)
	switch cmd {
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
	URL   string `json:"url"`
	Token string `json:"token,omitempty"`
}

func configPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = filepath.Join(os.Getenv("HOME"), ".config")
	}
	return filepath.Join(dir, "realm", "config.json")
}

func login(args []string) error {
	if len(args) < 1 {
		return errors.New("usage: realm login <url> [token]")
	}
	cfg := config{URL: strings.TrimRight(args[0], "/")}
	if len(args) > 1 {
		cfg.Token = args[1]
	}
	c := newClient(cfg)
	if c.token == "" && !strings.Contains(c.base, "/s/") {
		return errors.New("a server url needs a token; share links (…/s/<token>) don't")
	}
	if err := c.do("GET", "/", nil, nil); err != nil {
		return fmt.Errorf("can't reach %s: %w", c.base, err)
	}
	p := configPath()
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return err
	}
	b, _ := json.MarshalIndent(cfg, "", "  ")
	if err := os.WriteFile(p, b, 0o600); err != nil {
		return err
	}
	fmt.Println("saved", p)
	return nil
}

func loadClient() (*Client, error) {
	var cfg config
	if b, err := os.ReadFile(configPath()); err == nil {
		_ = json.Unmarshal(b, &cfg)
	}
	if v := os.Getenv("REALM_URL"); v != "" {
		cfg.URL, cfg.Token = v, ""
	}
	if v := os.Getenv("REALM_TOKEN"); v != "" {
		cfg.Token = v
	}
	if cfg.URL == "" {
		return nil, errors.New("not configured: run `realm login <url> <token>` or set REALM_URL")
	}
	return newClient(cfg), nil
}

func newClient(cfg config) *Client {
	base := strings.TrimRight(cfg.URL, "/")
	if !strings.Contains(base, "/s/") && !strings.HasSuffix(base, "/api") {
		base += "/api"
	}
	// No overall timeout (transfers can be huge), but don't hang forever on a dead server.
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.DialContext = (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext
	tr.TLSHandshakeTimeout = 15 * time.Second
	tr.ResponseHeaderTimeout = 2 * time.Minute
	return &Client{base: base, token: cfg.Token, http: &http.Client{Transport: tr}}
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
			return nil, errors.New("dump is empty")
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
		name := it.ID + ".txt"
		if it.Name != nil {
			name = *it.Name
		}
		dest := name
		if *out != "" {
			if st, err := os.Stat(*out); err == nil && st.IsDir() {
				dest = filepath.Join(*out, name)
			} else {
				dest = *out
			}
		}
		if err := c.download(it, dest); err != nil {
			return err
		}
		fmt.Println(dest)
	}
	return nil
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
	if len(args) == 0 {
		return errors.New("usage: realm rm <id>...")
	}
	for _, id := range args {
		it, err := c.resolve(id)
		if err == nil {
			err = c.do("DELETE", "/items/"+it.ID, nil, nil)
		}
		if err != nil {
			return fmt.Errorf("%s: %w", id, err)
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
	drop := fs.Bool("drop", false, "upload-only link to the whole dump")
	read := fs.Bool("read", false, "read-only link to the whole dump")
	full := fs.Bool("full", false, "read+write link to the whole dump")
	parse(fs, args)

	body := map[string]any{}
	switch {
	case *drop:
		body["scope"] = "drop"
	case *read:
		body["scope"] = "read"
	case *full:
		body["scope"] = "full"
	case fs.NArg() == 1:
		it, err := c.resolve(fs.Arg(0))
		if err != nil {
			return err
		}
		body["scope"], body["item"] = "item", it.ID
	default:
		return errors.New("usage: realm link <id> | --drop | --read | --full")
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
		if l.Item != nil {
			what += ":" + *l.Item
		}
		if l.Label != nil {
			what += " (" + *l.Label + ")"
		}
		fmt.Printf("%s  expires %-12s  uses %-6s  %s\n", l.URL, exp, uses, what)
	}
	return nil
}

func (c *Client) revoke(args []string) error {
	if len(args) == 0 {
		return errors.New("usage: realm revoke <token|url>...")
	}
	for _, t := range args {
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
	return err == nil && st.Mode()&os.ModeCharDevice != 0
}
