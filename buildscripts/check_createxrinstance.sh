#!/bin/bash
FILE="/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts/openmw-vr/apps/openmw/mwvr/openxrplatform.cpp"
echo "=== createXrInstance function ==="
sed -n '380,425p' "$FILE"
