#!/bin/bash
# Тільки сервер — без VR, без Android, для Termux на ARM
set -e
mkdir -p build-server && cd build-server
cmake .. \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_VR=OFF \
  -DBUILD_MULTIPLAYER=ON \
  -DBUILD_SERVER=ON \
  -DBUILD_OPENCS=OFF \
  -DBUILD_ANDROID=OFF \
  -DCMAKE_INSTALL_PREFIX=../install-server
make -j$(nproc)
make install
echo "Сервер: ./install-server/bin/tes3mp-server"
