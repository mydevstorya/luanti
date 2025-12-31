# 🐧 VocoCraft - Linux Build & Deployment Guide

## 📋 System Requirements

- **OS:** Ubuntu 20.04+, Debian 11+, Fedora 35+, Arch Linux
- **RAM:** 2 GB minimum, 4 GB recommended
- **GPU:** OpenGL 2.0+ (Mesa or proprietary drivers)
- **Disk:** 500 MB for build, 200 MB for installation

---

## 🔧 Installing Dependencies

### Ubuntu / Debian

```bash
sudo apt update
sudo apt install -y \
    git build-essential cmake \
    libpng-dev libjpeg-dev libgl1-mesa-dev \
    libsdl2-dev libopenal-dev libvorbis-dev \
    libfreetype-dev libsqlite3-dev libcurl4-openssl-dev \
    libluajit-5.1-dev libgmp-dev libjsoncpp-dev \
    zlib1g-dev gettext
```

### Fedora

```bash
sudo dnf install -y \
    git gcc-c++ cmake \
    libpng-devel libjpeg-devel mesa-libGL-devel \
    SDL2-devel openal-devel libvorbis-devel \
    freetype-devel sqlite-devel libcurl-devel \
    luajit-devel gmp-devel jsoncpp-devel \
    zlib-devel gettext
```

### Arch Linux

```bash
sudo pacman -S --noconfirm \
    git base-devel cmake \
    libpng libjpeg mesa sdl2 \
    openal libvorbis freetype2 sqlite curl \
    luajit gmp jsoncpp gettext
```

### openSUSE

```bash
sudo zypper install -y \
    git gcc-c++ cmake \
    libpng16-devel libjpeg8-devel Mesa-libGL-devel \
    SDL2-devel openal-soft-devel libvorbis-devel \
    freetype2-devel sqlite3-devel libcurl-devel \
    luajit-devel gmp-devel jsoncpp-devel \
    zlib-devel gettext-tools
```

---

## 🚀 Quick Build (Recommended)

```bash
# Clone the repository
git clone https://github.com/your-org/vococraft.git
cd vococraft

# Run the build script
chmod +x build_linux.sh
./build_linux.sh

# Launch the game
./dist_linux/bin/luanti
```

### Clean Rebuild

```bash
./build_linux.sh --clean
```

---

## 🛠️ Manual Build

```bash
# Create build directory
mkdir build && cd build

# Configure
cmake .. \
    -DCMAKE_BUILD_TYPE=Release \
    -DRUN_IN_PLACE=TRUE \
    -DENABLE_GETTEXT=TRUE \
    -DENABLE_FREETYPE=TRUE \
    -DENABLE_CURL=TRUE \
    -DENABLE_SOUND=TRUE \
    -DBUILD_CLIENT=TRUE \
    -DBUILD_SERVER=TRUE

# Build (uses all CPU cores)
make -j$(nproc)

# Run
./bin/luanti
```

---

## 📦 System Installation

```bash
cd build
sudo make install
```

Default installation path is `/usr/local/`. To change:

```bash
cmake .. -DCMAKE_INSTALL_PREFIX=/opt/vococraft
sudo make install
```

---

## 🖥️ Running a Dedicated Server

### Quick Start

```bash
# From the build directory
./bin/luanti --server --world ./worlds/myworld --gameid vococraft
```

### Creating a systemd Service

```bash
sudo nano /etc/systemd/system/vococraft.service
```

```ini
[Unit]
Description=VocoCraft Server
After=network.target

[Service]
Type=simple
User=vococraft
WorkingDirectory=/opt/vococraft
ExecStart=/opt/vococraft/bin/luanti --server --config /opt/vococraft/minetest.conf
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
# Create service user
sudo useradd -r -s /bin/false vococraft

# Set directory permissions
sudo chown -R vococraft:vococraft /opt/vococraft

# Enable and start the service
sudo systemctl daemon-reload
sudo systemctl enable vococraft
sudo systemctl start vococraft

# Check status
sudo systemctl status vococraft

# View logs
sudo journalctl -u vococraft -f
```

---

## 🔥 Firewall Configuration

### UFW (Ubuntu/Debian)

```bash
sudo ufw allow 30000/udp comment "VocoCraft"
sudo ufw reload
```

### firewalld (Fedora/CentOS)

```bash
sudo firewall-cmd --permanent --add-port=30000/udp
sudo firewall-cmd --reload
```

### iptables

```bash
sudo iptables -A INPUT -p udp --dport 30000 -j ACCEPT
sudo iptables-save | sudo tee /etc/iptables/rules.v4
```

---

## ⚙️ Server Configuration

Create `minetest.conf` in your server directory:

```properties
# === Identity ===
server_name = VocoCraft Server
server_description = Welcome to VocoCraft Server!
server_address = your-server.com
server_url = 

# === Network ===
port = 30000
max_users = 50

# === Server List ===
server_announce = true
serverlist_url =

# === Gameplay ===
default_game = vococraft
creative_mode = false
enable_damage = true
enable_pvp = true

# === Admin ===
name = admin

# === Security ===
disallow_empty_password = true
```

---

## 🐳 Docker Deployment

```bash
# Build the image
docker build -t vococraft .

# Run the server
docker run -d \
    --name vococraft-server \
    -p 30000:30000/udp \
    -v ./worlds:/app/worlds \
    -v ./minetest.conf:/app/minetest.conf \
    vococraft --server
```

---

## ❓ Troubleshooting

### Error: "libGL.so not found"

```bash
# Ubuntu/Debian
sudo apt install libgl1-mesa-glx

# Fedora
sudo dnf install mesa-libGL
```

### Error: "Could not find LuaJIT"

```bash
# Ubuntu/Debian
sudo apt install libluajit-5.1-dev

# Or build without LuaJIT (slower)
cmake .. -DENABLE_LUAJIT=FALSE
```

### Low FPS

```bash
# Check GPU drivers
glxinfo | grep "OpenGL renderer"

# For NVIDIA
sudo apt install nvidia-driver-535

# For AMD
sudo apt install mesa-vulkan-drivers
```

### Server Not Visible in List

1. Verify port 30000/udp is open
2. Check `server_announce = true` in config
3. Verify `serverlist_url` points to correct API
4. Wait 1-2 minutes after server start

---

