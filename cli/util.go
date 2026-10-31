// Flag parsing, sizes and durations, and transfer progress.
package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"
)

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
