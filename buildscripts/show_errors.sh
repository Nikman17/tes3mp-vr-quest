#!/bin/bash
grep "error:" /tmp/openmw_build3.txt | grep -v "warning" | grep -v "note:"
