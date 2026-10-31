// Where you are, and the realms on the server.
package main

import (
	"errors"
	"flag"
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"
)

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
			fmt.Println("cloudflare ok")
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
		return errors.New("realms need realm login, not a link")
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
		fmt.Printf("made “%s” (realm use %s)\n", r.Name, quoteArg(r.Name))
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
		if err := confirm(fmt.Sprintf("Delete “%s” and its %d item%s?", r.Name, r.Count, plural(int(r.Count))), *yes); err != nil {
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
		return errors.New("realm use needs realm login, not a link")
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
		return errors.New("add -y to confirm")
	}
	fmt.Fprint(os.Stderr, question, " [y/N] ")
	var answer string
	fmt.Scanln(&answer)
	if a := strings.ToLower(strings.TrimSpace(answer)); a == "y" || a == "yes" {
		return nil
	}
	return errors.New("cancelled")
}
