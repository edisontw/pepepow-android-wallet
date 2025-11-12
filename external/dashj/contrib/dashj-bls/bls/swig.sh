#! /bin/sh
#  -debug-symbols
swig -v -c++ -java -package org.dashj.bls -outdir src/main/java/org/dashj/bls -o src/main/cpp/bls-signatures-v1.cpp src/main/swig/bls-signatures.i