#!/bin/bash
# ============================================
# VocoCraft Linux Build Script
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build_linux"
INSTALL_DIR="$SCRIPT_DIR/dist_linux"
JOBS=$(nproc 2>/dev/null || echo 4)

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}╔════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║     VocoCraft Linux Build Script       ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════╝${NC}"

# --- Check for dependencies ---
check_deps() {
    echo -e "\n${YELLOW}[1/4] Checking dependencies...${NC}"
    
    local missing=()
    
    # Required commands
    for cmd in cmake gcc g++ git pkg-config; do
        if ! command -v $cmd &> /dev/null; then
            missing+=("$cmd")
        fi
    done
    
    if [ ${#missing[@]} -ne 0 ]; then
        echo -e "${RED}Missing required tools: ${missing[*]}${NC}"
        echo ""
        echo "Install dependencies based on your distro:"
        echo ""
        echo "Ubuntu/Debian:"
        echo "  sudo apt install git build-essential cmake libpng-dev libjpeg-dev \\"
        echo "      libgl1-mesa-dev libsdl2-dev libopenal-dev libvorbis-dev \\"
        echo "      libfreetype-dev libsqlite3-dev libcurl4-openssl-dev \\"
        echo "      libluajit-5.1-dev libgmp-dev libjsoncpp-dev zlib1g-dev gettext"
        echo ""
        echo "Fedora:"
        echo "  sudo dnf install git gcc-c++ cmake libpng-devel libjpeg-devel \\"
        echo "      mesa-libGL-devel SDL2-devel openal-devel libvorbis-devel \\"
        echo "      freetype-devel sqlite-devel libcurl-devel luajit-devel \\"
        echo "      gmp-devel jsoncpp-devel zlib-devel gettext"
        echo ""
        echo "Arch Linux:"
        echo "  sudo pacman -S git base-devel cmake libpng libjpeg mesa sdl2 \\"
        echo "      openal libvorbis freetype2 sqlite curl luajit gmp jsoncpp gettext"
        echo ""
        exit 1
    fi
    
    echo -e "${GREEN}✓ All required tools found${NC}"
}

# --- Clean build ---
clean_build() {
    if [ "$1" == "--clean" ] || [ "$1" == "-c" ]; then
        echo -e "\n${YELLOW}Cleaning previous build...${NC}"
        rm -rf "$BUILD_DIR"
        rm -rf "$INSTALL_DIR"
        echo -e "${GREEN}✓ Cleaned${NC}"
    fi
}

# --- Configure ---
configure() {
    echo -e "\n${YELLOW}[2/4] Configuring CMake...${NC}"
    
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"
    
    cmake "$SCRIPT_DIR" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
        -DRUN_IN_PLACE=TRUE \
        -DENABLE_GETTEXT=TRUE \
        -DENABLE_FREETYPE=TRUE \
        -DENABLE_CURL=TRUE \
        -DENABLE_SOUND=TRUE \
        -DBUILD_CLIENT=TRUE \
        -DBUILD_SERVER=TRUE
    
    echo -e "${GREEN}✓ Configuration complete${NC}"
}

# --- Build ---
build() {
    echo -e "\n${YELLOW}[3/4] Building with $JOBS threads...${NC}"
    
    cd "$BUILD_DIR"
    make -j$JOBS
    
    echo -e "${GREEN}✓ Build complete${NC}"
}

# --- Install ---
install_local() {
    echo -e "\n${YELLOW}[4/4] Installing to $INSTALL_DIR...${NC}"
    
    cd "$BUILD_DIR"
    make install
    
    # Create required directories
    mkdir -p "$INSTALL_DIR/games"
    mkdir -p "$INSTALL_DIR/worlds"
    mkdir -p "$INSTALL_DIR/mods"
    
    # Copy game data (VocoCraft)
    if [ -d "$SCRIPT_DIR/games/vococraft" ]; then
        echo "Copying VocoCraft game..."
        rm -rf "$INSTALL_DIR/games/vococraft"
        cp -r "$SCRIPT_DIR/games/vococraft" "$INSTALL_DIR/games/"
        echo -e "${GREEN}✓ VocoCraft game copied${NC}"
    else
        echo -e "${RED}WARNING: games/vococraft not found!${NC}"
    fi
    
    # Copy builtin (required for menu)
    if [ -d "$SCRIPT_DIR/builtin" ]; then
        echo "Copying builtin..."
        rm -rf "$INSTALL_DIR/builtin"
        cp -r "$SCRIPT_DIR/builtin" "$INSTALL_DIR/"
    fi
    
    # Copy textures
    if [ -d "$SCRIPT_DIR/textures" ]; then
        echo "Copying textures..."
        rm -rf "$INSTALL_DIR/textures"
        cp -r "$SCRIPT_DIR/textures" "$INSTALL_DIR/"
    fi
    
    # Copy fonts
    if [ -d "$SCRIPT_DIR/fonts" ]; then
        echo "Copying fonts..."
        rm -rf "$INSTALL_DIR/fonts"
        cp -r "$SCRIPT_DIR/fonts" "$INSTALL_DIR/"
    fi
    
    # Copy locale
    if [ -d "$SCRIPT_DIR/locale" ]; then
        echo "Copying locale..."
        rm -rf "$INSTALL_DIR/locale"
        cp -r "$SCRIPT_DIR/locale" "$INSTALL_DIR/"
    fi
    
    # Copy client shaders
    if [ -d "$SCRIPT_DIR/client" ]; then
        echo "Copying client data..."
        rm -rf "$INSTALL_DIR/client"
        cp -r "$SCRIPT_DIR/client" "$INSTALL_DIR/"
    fi
    
    # Copy default config if exists
    if [ -f "$SCRIPT_DIR/minetest.conf.example" ]; then
        cp "$SCRIPT_DIR/minetest.conf.example" "$INSTALL_DIR/"
    fi
    
    echo -e "${GREEN}✓ Installation complete${NC}"
}

# --- Main ---
main() {
    clean_build "$1"
    check_deps
    configure
    build
    install_local
    
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║         Build Successful!              ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════╝${NC}"
    echo ""
    echo "To run VocoCraft:"
    echo "  cd $INSTALL_DIR"
    echo "  ./bin/luanti"
    echo ""
    echo "Or run directly:"
    echo "  $INSTALL_DIR/bin/luanti"
    echo ""
}

# --- Help ---
if [ "$1" == "--help" ] || [ "$1" == "-h" ]; then
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --clean, -c    Clean build directory before building"
    echo "  --help, -h     Show this help message"
    echo ""
    exit 0
fi

main "$@"
