// Logging in with Cloudflare: OAuth with PKCE against realm's Cloudflare client, a one-shot callback on
// localhost, then finding the realm worker on the chosen account.
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
	cfClientID  = "e48e69845742def220449dec54f713b3" // realm's OAuth client; public, not a secret
	cfAuthorize = "https://dash.cloudflare.com/oauth2/auth"
	cfTokenURL  = "https://dash.cloudflare.com/oauth2/token"
	cfRevokeURL = "https://dash.cloudflare.com/oauth2/revoke"
	cfAPI       = "https://api.cloudflare.com/client/v4"
	cfPort      = 53134 // registered as http://localhost:53134/oauth/callback
	workerName  = "realm"
)

// cfScopes are what the client asks for, by the ids Cloudflare checks them under ("Workers Admin" is
// workers-scripts.admin). The custom-domain ones are optional on the consent screen. offline_access
// is what gets a refresh token: access tokens last an hour.
// REALM_CF_SCOPES replaces the list, for trying scope names against the client's registration.
var cfScopes = []string{
	"account-settings.read", "workers-scripts.admin", "d1.write", "workers-r2.write",
	"workers-routes.write", "dns.write", "zone.read", "offline_access",
}

// cfAuth is a Cloudflare login, kept so the CLI can deploy and update the worker later.
type cfAuth struct {
	Access  string `json:"access"`
	Refresh string `json:"refresh,omitempty"`
	Expires int64  `json:"expires"` // unix seconds
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
		cfg := readConfig()
		cfg.Cloudflare = tok
		if err := saveConfig(cfg); err != nil {
			return err
		}
		return fmt.Errorf("no %q worker on %s: deploy it from the app, or with npm run deploy in worker/", workerName, acc.Name)
	}

	script := "/accounts/" + acc.ID + "/workers/scripts/" + workerName
	origin, err := workerOrigin(tok.Access, acc.ID, script)
	if err != nil {
		return err
	}

	// The worker takes a Cloudflare login as its owner's once it knows which account it's on.
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

	// No owner token: the Cloudflare login is the key. Saved first, since the client reads it from there.
	cfg := config{URL: origin, Cloudflare: tok}
	if err := saveConfig(cfg); err != nil {
		return err
	}
	c := newClient(cfg)
	var info Info
	for try := 0; try < 5; try++ { // a new secret takes a few seconds to reach the worker
		if err = c.do("GET", "/info", nil, &info); err == nil || !strings.Contains(err.Error(), "(401)") {
			break
		}
		time.Sleep(2 * time.Second)
	}
	if err != nil {
		if strings.Contains(err.Error(), "(401)") {
			return errors.New("worker too old for Cloudflare login: redeploy it, then realm login")
		}
		return err
	}
	warnMismatch(info)
	return c.status()
}

// workerOrigin is where the worker answers: its custom domain if it has one, else its workers.dev address.
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

// cfOAuth runs the browser login: authorization code with PKCE, caught on localhost.
func cfOAuth() (*cfAuth, error) {
	verifier := randomString(48)
	sum := sha256.Sum256([]byte(verifier))
	challenge := base64.RawURLEncoding.EncodeToString(sum[:])
	state := randomString(24)
	redirect := fmt.Sprintf("http://localhost:%d/oauth/callback", cfPort)
	scopes := cfScopes
	if v := os.Getenv("REALM_CF_SCOPES"); v != "" {
		scopes = strings.Fields(v)
	}

	q := url.Values{}
	q.Set("response_type", "code")
	q.Set("client_id", cfClientID)
	q.Set("redirect_uri", redirect)
	q.Set("scope", strings.Join(scopes, " "))
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
	mux.HandleFunc("/oauth/callback", func(w http.ResponseWriter, r *http.Request) {
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
	// "localhost" may resolve to either address family; listen on both.
	listening := 0
	for _, host := range []string{"127.0.0.1", "::1"} {
		if ln, err := net.Listen("tcp", net.JoinHostPort(host, strconv.Itoa(cfPort))); err == nil {
			listening++
			go srv.Serve(ln)
		}
	}
	if listening == 0 {
		return nil, fmt.Errorf("port %d is busy", cfPort)
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
		"redirect_uri":  {redirect},
		"client_id":     {cfClientID},
		"code_verifier": {verifier},
	}, "")
}

// errCFRejected means Cloudflare turned the login down: the only way on is logging in again.
var errCFRejected = errors.New("Cloudflare login expired: realm login")

// cfExchange posts to the token endpoint (form-encoded; it doesn't take JSON). A reply without a new
// refresh token leaves [keep] valid, so it's kept.
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

// cfAccess is a usable Cloudflare access token from the saved login, refreshed first if it has run out.
// Refresh tokens are single-use, so the config is read fresh and the new one saved straight away. When
// Cloudflare can't be reached the login is left as it is: the refresh token may still be good.
func cfAccess() (string, error) {
	cfg := readConfig()
	auth := cfg.Cloudflare
	if auth == nil {
		return "", errors.New("not logged in: realm login")
	}
	if time.Now().Unix() < auth.Expires-60 {
		return auth.Access, nil
	}
	if auth.Refresh == "" {
		return "", errCFRejected
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
		return "", err
	}
	next.Account = auth.Account
	cfg.Cloudflare = next
	if err := saveConfig(cfg); err != nil {
		return "", err
	}
	return next.Access, nil
}

// cfRevoke ends the Cloudflare login on Cloudflare's side too. Best effort: logging out locally goes ahead.
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

// cfGet calls the Cloudflare API and decodes its "result".
func cfGet(token, path string, out any) error {
	return cfSend(token, "GET", path, nil, out)
}

// cfSend calls the Cloudflare API with an optional JSON body and decodes its "result" into out.
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
