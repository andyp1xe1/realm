package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	cfClientID  = "e48e69845742def220449dec54f713b3"
	cfAuthorize = "https://dash.cloudflare.com/oauth2/auth"
	cfRevokeURL = "https://dash.cloudflare.com/oauth2/revoke"
	cfAPI       = "https://api.cloudflare.com/client/v4"
	cfRedirect  = "http://localhost:53134/oauth/callback"
	workerName  = "realm"
)

var cfTokenURL = "https://dash.cloudflare.com/oauth2/token"

var cfScopes = []string{
	"account-settings.read", "workers-scripts.admin", "d1.write", "workers-r2.write",
	"workers-routes.write", "dns.write", "zone.read", "offline_access",
}

type cfAuth struct {
	Access  string `json:"access"`
	Refresh string `json:"refresh,omitempty"`
	Expires int64  `json:"expires"`
	Account string `json:"account,omitempty"`
}

func loginCloudflare() error {
	tok, err := cfOAuth()
	if err != nil {
		return err
	}

	var accounts []struct{ ID, Name string }
	if err := cfGet(tok.Access, "/accounts", &accounts); err != nil {
		return fmt.Errorf("listing your accounts: %w", err)
	}
	if len(accounts) == 0 {
		return errors.New("no Cloudflare account picked on the consent screen")
	}
	acc := accounts[0]
	if len(accounts) > 1 {
		fmt.Println("account:")
		for i, a := range accounts {
			fmt.Printf("  %d  %s\n", i+1, a.Name)
		}
		n, err := strconv.Atoi(ask("> "))
		if err != nil || n < 1 || n > len(accounts) {
			return errors.New("no such account")
		}
		acc = accounts[n-1]
	}
	tok.Account = acc.ID

	var scripts []struct{ ID string }
	if err := cfGet(tok.Access, "/accounts/"+acc.ID+"/workers/scripts", &scripts); err != nil {
		return fmt.Errorf("listing workers on %s: %w", acc.Name, err)
	}
	found := false
	for _, s := range scripts {
		found = found || s.ID == workerName
	}
	if !found {
		if err := updateConfig(func(cfg *config) { cfg.Cloudflare = tok }); err != nil {
			return err
		}
		return fmt.Errorf("no %q worker on %s: deploy it from the app, or with npm run deploy in worker/", workerName, acc.Name)
	}

	script := "/accounts/" + acc.ID + "/workers/scripts/" + workerName
	origin, err := workerOrigin(tok.Access, acc.ID, script)
	if err != nil {
		return err
	}

	var secrets []struct{ Name string }
	if err := cfGet(tok.Access, script+"/secrets", &secrets); err != nil {
		return fmt.Errorf("worker secrets: %w", err)
	}
	has := false
	for _, s := range secrets {
		has = has || s.Name == "REALM_ACCOUNT"
	}
	if !has {
		body := map[string]string{"name": "REALM_ACCOUNT", "text": acc.ID, "type": "secret_text"}
		if err := cfSend(tok.Access, "PUT", script+"/secrets", body, nil); err != nil {
			return fmt.Errorf("setting REALM_ACCOUNT: %w", err)
		}
	}

	cfg := config{URL: origin, Cloudflare: tok, Links: readConfig().Links}
	if err := saveConfig(cfg); err != nil {
		return err
	}
	c := newClient(cfg)
	info, err := ownerInfo(c, !has)
	if err != nil {
		return err
	}
	warnMismatch(info)
	return c.status()
}

var ownerPoll = 2 * time.Second

func ownerInfo(c *Client, claimed bool) (Info, error) {
	var info Info
	srv := c.at(c.server)
	err := srv.do("GET", "/info", nil, &info)
	for try := 1; claimed && try < 5 && hasStatus(err, http.StatusUnauthorized); try++ {
		time.Sleep(ownerPoll)
		err = srv.do("GET", "/info", nil, &info)
	}
	if err != nil {
		return info, fmt.Errorf("the worker refused your Cloudflare login: %w", err)
	}
	return info, nil
}

func workerOrigin(token, account, script string) (string, error) {
	var domains []struct{ Hostname string }
	if err := cfGet(token, "/accounts/"+account+"/workers/domains?service="+workerName, &domains); err != nil {
		return "", fmt.Errorf("worker domains: %w", err)
	}
	if len(domains) > 0 {
		return "https://" + domains[0].Hostname, nil
	}
	var on struct{ Enabled bool }
	if err := cfGet(token, script+"/subdomain", &on); err != nil {
		return "", fmt.Errorf("workers.dev address: %w", err)
	}
	var sub struct{ Subdomain string }
	if err := cfGet(token, "/accounts/"+account+"/workers/subdomain", &sub); err != nil {
		return "", fmt.Errorf("workers.dev address: %w", err)
	}
	if !on.Enabled || sub.Subdomain == "" {
		return "", errors.New("the worker has no address: turn on its workers.dev address, or give it a custom domain")
	}
	return fmt.Sprintf("https://%s.%s.workers.dev", workerName, sub.Subdomain), nil
}

func ask(prompt string) string {
	fmt.Fprint(os.Stderr, prompt)
	line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
	return strings.TrimSpace(line)
}

func cfOAuth() (*cfAuth, error) {
	verifier := randomString(48)
	sum := sha256.Sum256([]byte(verifier))
	challenge := base64.RawURLEncoding.EncodeToString(sum[:])
	state := randomString(24)
	callback, _ := url.Parse(cfRedirect)

	q := url.Values{}
	q.Set("response_type", "code")
	q.Set("client_id", cfClientID)
	q.Set("redirect_uri", cfRedirect)
	q.Set("scope", strings.Join(cfScopes, " "))
	q.Set("state", state)
	q.Set("code_challenge", challenge)
	q.Set("code_challenge_method", "S256")
	authURL := cfAuthorize + "?" + q.Encode()

	type result struct {
		code string
		err  error
	}
	done := make(chan result, 1)
	mux := http.NewServeMux()
	mux.HandleFunc(callback.Path, func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Query()
		var res result
		switch {
		case p.Get("state") != state:
			res.err = errors.New("state mismatch, try again")
		case p.Get("error") != "":
			res.err = fmt.Errorf("Cloudflare said %s: %s", p.Get("error"), p.Get("error_description"))
		default:
			res.code = p.Get("code")
		}
		msg := "Logged in. You can close this tab and go back to the terminal."
		if res.err != nil {
			msg = "Didn't work: " + res.err.Error()
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, `<!doctype html><meta charset=utf-8><title>realm</title><body style="font:16px system-ui;padding:40px">%s</body>`, html.EscapeString(msg))
		select {
		case done <- res:
		default:
		}
	})
	srv := &http.Server{Handler: mux}
	listening := 0
	for _, host := range []string{"127.0.0.1", "::1"} {
		if ln, err := net.Listen("tcp", net.JoinHostPort(host, callback.Port())); err == nil {
			listening++
			go srv.Serve(ln)
		}
	}
	if listening == 0 {
		return nil, fmt.Errorf("port %s is busy", callback.Port())
	}
	defer srv.Shutdown(context.Background())

	fmt.Println("opening browser:")
	fmt.Println(authURL)
	openBrowser(authURL)

	var res result
	select {
	case res = <-done:
	case <-time.After(5 * time.Minute):
		return nil, errors.New("timed out waiting for the browser")
	}
	if res.err != nil {
		return nil, res.err
	}

	return cfExchange(url.Values{
		"grant_type":    {"authorization_code"},
		"code":          {res.code},
		"redirect_uri":  {cfRedirect},
		"client_id":     {cfClientID},
		"code_verifier": {verifier},
	}, "")
}

var errCFRejected = errors.New("Cloudflare login expired: realm login")

func cfExchange(form url.Values, keep string) (*cfAuth, error) {
	resp, err := (&http.Client{Timeout: 30 * time.Second}).PostForm(cfTokenURL, form)
	if err != nil {
		return nil, fmt.Errorf("can't reach Cloudflare: %w", err)
	}
	defer resp.Body.Close()
	var t struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int64  `json:"expires_in"`
		Error        string `json:"error"`
		Description  string `json:"error_description"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&t); err != nil {
		return nil, fmt.Errorf("token response (http %d): %w", resp.StatusCode, err)
	}
	if t.AccessToken == "" {
		if resp.StatusCode == 400 || resp.StatusCode == 401 || t.Error == "invalid_grant" {
			return nil, errCFRejected
		}
		return nil, fmt.Errorf("no token: %s %s", t.Error, t.Description)
	}
	if t.RefreshToken == "" {
		t.RefreshToken = keep
	}
	return &cfAuth{Access: t.AccessToken, Refresh: t.RefreshToken, Expires: time.Now().Unix() + t.ExpiresIn}, nil
}

func (a *cfAuth) fresh() bool {
	return time.Now().Unix() < a.Expires-60
}

func cfAccess() (*cfAuth, error) {
	if auth := readConfig().Cloudflare; auth != nil && auth.fresh() {
		return auth, nil
	}
	defer lockConfig()()
	cfg := readConfig()
	auth := cfg.Cloudflare
	if auth == nil {
		return nil, errors.New("not logged in: realm login")
	}
	if auth.fresh() {
		return auth, nil
	}
	if auth.Refresh == "" {
		return nil, errCFRejected
	}
	next, err := cfExchange(url.Values{
		"grant_type":    {"refresh_token"},
		"refresh_token": {auth.Refresh},
		"client_id":     {cfClientID},
	}, auth.Refresh)
	if err != nil {
		if errors.Is(err, errCFRejected) {
			cfg.Cloudflare = nil
			_ = saveConfig(cfg)
		}
		return nil, err
	}
	next.Account = auth.Account
	cfg.Cloudflare = next
	if err := saveConfig(cfg); err != nil {
		return nil, err
	}
	return next, nil
}

func cfRevoke(auth *cfAuth) {
	if auth == nil || auth.Refresh == "" {
		return
	}
	_, _ = (&http.Client{Timeout: 10 * time.Second}).PostForm(cfRevokeURL, url.Values{
		"client_id":       {cfClientID},
		"token_type_hint": {"refresh_token"},
		"token":           {auth.Refresh},
	})
}

func cfGet(token, path string, out any) error {
	return cfSend(token, "GET", path, nil, out)
}

func cfSend(token, method, path string, in, out any) error {
	var body io.Reader
	if in != nil {
		b, _ := json.Marshal(in)
		body = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, cfAPI+path, body)
	req.Header.Set("Authorization", "Bearer "+token)
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var env struct {
		Success bool
		Errors  []struct {
			Code    int
			Message string
		}
		Result json.RawMessage
	}
	if err := json.NewDecoder(resp.Body).Decode(&env); err != nil {
		return fmt.Errorf("http %d", resp.StatusCode)
	}
	if !env.Success {
		var msgs []string
		for _, e := range env.Errors {
			msgs = append(msgs, fmt.Sprintf("%s (%d)", e.Message, e.Code))
		}
		return errors.New(strings.Join(msgs, "; "))
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(env.Result, out)
}

func openBrowser(u string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		cmd = exec.Command("open", u)
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", u)
	default:
		cmd = exec.Command("xdg-open", u)
	}
	_ = cmd.Start()
}

func randomString(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)[:n]
}
