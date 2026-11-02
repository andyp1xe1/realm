# `nix-shell` here gives everything realm needs: JDK for Gradle, Go for the CLI, Node for the worker, ktlint for the hook,
# and the native libraries the Compose desktop app loads at runtime (NixOS has no global /usr/lib).
{ pkgs ? import (builtins.getFlake "nixpkgs") { } }:
let
  desktopLibs = with pkgs; [
    libGL # Skiko (Compose's renderer)
    libx11 libxext libxrender libxi libxtst libxrandr libxcursor libxkbcommon # AWT window, input
    fontconfig freetype # text
    alsa-lib # javax.sound: voice recording
  ];
in
pkgs.mkShell {
  packages = [ pkgs.jdk21 pkgs.go pkgs.nodejs_22 pkgs.ktlint ];
  shellHook = ''
    export JAVA_HOME=${pkgs.jdk21.home}
    export ANDROID_HOME=''${ANDROID_HOME:-$HOME/Android/Sdk}
    export LD_LIBRARY_PATH=${pkgs.lib.makeLibraryPath desktopLibs}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
  '';
}
