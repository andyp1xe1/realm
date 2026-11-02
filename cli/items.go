// The item commands: adding, listing, getting, editing and removing items, and share links.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"mime"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

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
	q := fmt.Sprintf("Delete %d items?", len(items))
	if len(items) == 1 {
		q = "Delete " + describe(items[0]) + "?"
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
	q := "Revoke link?"
	if fs.NArg() > 1 {
		q = fmt.Sprintf("Revoke %d links?", fs.NArg())
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
