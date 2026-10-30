# `nix-shell` here gives everything realm needs: JDK for Gradle, Go for the CLI, Node for the worker,
# and the native libraries the Compose desktop app loads at runtime (NixOS has no global /usr/lib).
{ pkgs ? import (builtins.getFlake "nixpkgs") { } }:
let
  desktopLibs = with pkgs; [
    libGL # Skiko (Compose's renderer)
    libx11 libxext libxrender libxi libxtst libxrandr libxcursor libxkbcommon # AWT window, input
    fontconfig freetype # text
    alsa-lib # javax.sound: voice recording
  ];
  # The AWT file dialog is GTK's; GLib aborts the JVM if it can't find GTK's settings schemas.
  schemas = with pkgs; [ gtk3 gsettings-desktop-schemas ];
in
pkgs.mkShell {
  packages = [ pkgs.jdk21 pkgs.go pkgs.nodejs_22 ];
  shellHook = ''
    export JAVA_HOME=${pkgs.jdk21.home}
    export ANDROID_HOME=''${ANDROID_HOME:-$HOME/Android/Sdk}
    export XDG_DATA_DIRS=${pkgs.lib.concatMapStringsSep ":" (p: "${p}/share/gsettings-schemas/${p.name}") schemas}''${XDG_DATA_DIRS:+:$XDG_DATA_DIRS}
    export LD_LIBRARY_PATH=${pkgs.lib.makeLibraryPath desktopLibs}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
  '';
}
