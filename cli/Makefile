# `make` builds for this machine; `make dist` cross-compiles static binaries into dist/.
TARGETS = linux/amd64 linux/arm64 darwin/arm64 darwin/amd64 windows/amd64 android/arm64

realm: *.go
	CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o realm .

dist:
	@for t in $(TARGETS); do \
		os=$${t%/*}; arch=$${t#*/}; ext=$$( [ $$os = windows ] && echo .exe ); \
		echo "dist/realm-$$os-$$arch$$ext"; \
		CGO_ENABLED=0 GOOS=$$os GOARCH=$$arch go build -trimpath -ldflags="-s -w" -o dist/realm-$$os-$$arch$$ext . || exit 1; \
	done

.PHONY: dist
