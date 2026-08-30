#!/bin/bash
sed -i '1s/^/#include <limits>\n/' src/loader/android_utilities.cpp
sed -i 's#return (access(path.c_str(), F_OK) != -1);#if (path.find("!/") != std::string::npos) return true; return (access(path.c_str(), F_OK) != -1);#' src/common/filesystem_utils.cpp
