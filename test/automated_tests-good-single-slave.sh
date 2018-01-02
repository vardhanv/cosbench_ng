
BKT=Vishnu_test

echo "With Simple starter test"
../master-start.sh -b $BKT -c PUT -m 100 -r 10 -s 1
../master-start.sh -b $BKT -c GET -m 100 -r 10 -s 1
../master-start.sh -b $BKT -c GET -m 100 -r 10 -g start=10,end=900 -s 1

echo "With Run to completion"
../master-start.sh -b $BKT -c PUT -m 100 -r 10 -f -s 1
../master-start.sh -b $BKT -c GET -m 100 -r 10 -f -s 1
../master-start.sh -b $BKT -c GET -m 100 -r 10 -g start=10,end=900 -f -s 1


echo "edge condition with run to completion"
../master-start.sh -b $BKT -c PUT -m 1 -r 1 -s 1
../master-start.sh -b $BKT -c GET -m 1 -r 1 -s 1
../master-start.sh -b $BKT -c GET -m 1 -r 1 -g start=10,end=900 -s 1

echo "with fake s3"
../master-start.sh -b $BKT -c PUT -m 1 -r 1 -k 1 -s 1
../master-start.sh -b $BKT -c GET -m 1 -r 1 -k 1 -s 1
../master-start.sh -b $BKT -c GET -m 1 -r 1 -g start=10,end=900 -k 1 -s 1

echo "with fake s3 and run to completion"
../master-start.sh -b $BKT -c PUT -m 1 -r 1 -k 1 -f -s 1
../master-start.sh -b $BKT -c GET -m 1 -r 1 -k 1 -f -s 1
../master-start.sh -b $BKT -c GET -m 1 -r 1 -g start=10,end=900 -k 1 -f -s 1

echo "with fake s3 and run to completion and wrong bucket"
../master-start.sh -b CRAZY_BKT -c PUT -m 1 -r 1 -k 1 -f -s 1
../master-start.sh -b CRAZY_BKT -c GET -m 1 -r 1 -k 1 -f -s 1
../master-start.sh -b CRAZY_BKT -c GET -m 1 -r 1 -g start=10,end=900 -k 1 -f -s 1

echo "Large number of S3 Ops"
../master-start.sh -b $BKT -c PUT -m 10000 -r 500 -s 1
../master-start.sh -b $BKT -c GET -m 10000 -r 500 -s 1
../master-start.sh -b $BKT -c GET -m 10000 -r 500 -g start=10,end=900 -s 1

echo "Large object 5MB S3 Ops, run to completion"
../master-start.sh -b $BKT -c PUT -m 10 -r 5 -z 5000 -f -s 1
../master-start.sh -b $BKT -c GET -m 10 -r 5 -z 5000 -f -s 1
../master-start.sh -b $BKT -c GET -m 10 -r 5 -g start=400000,end=800000 -f -s 1
