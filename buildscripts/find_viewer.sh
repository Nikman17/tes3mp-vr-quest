#!/bin/bash
grep -rn "new osgViewer::Viewer\|mViewer.*new\|g_viewer\s*=" \
    /mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts/openmw-vr/apps/openmw/ 2>/dev/null
