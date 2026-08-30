#!/bin/bash
grep "error:" /tmp/openmw_build4.txt | grep -v "warning" | grep -v "note:"
