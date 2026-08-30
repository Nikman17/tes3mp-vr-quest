#!/bin/bash
grep "^e: \|error:" /tmp/gradle_build2.txt | head -20
