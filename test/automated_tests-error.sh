#!/bin/bash

BKT=Vishnu_test

echo "error condition tests"

echo "wrong range read"
../master-start.sh -b $BKT -c PUT -m 100 -r 10 -f
../master-start.sh -b $BKT -c GET -m 100 -r 10 -f
../master-start.sh -b $BKT -c GET -m 100 -r 10 -g start=10,end=1 -f 

echo "with wrong bucket"
../master-start.sh -b CRAZY_BKT -c PUT -m 1 -r 1  -f 
../master-start.sh -b CRAZY_BKT -c GET -m 1 -r 1  -f 
../master-start.sh -b CRAZY_BKT -c GET -m 1 -r 1 -g start=10,end=900 -f 

