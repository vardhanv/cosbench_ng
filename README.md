# cosbench_ng
Cosbench - NG: Redefining S3 Benchmarking


S3 Performance tester, that allows you to add multiple slaves and scale up your testing.

## Features

* Limit load generated to specific objects/second rate
* Configure multiple slaves, so that you can scale out your tests
* Generates tests results in a csv format, to allow historic logging
* Measure time to first and time to last byte
* Request New Features / Log Bugs here https://github.com/vardhanv/cosbench_ng/issues

## How To Execute
### On the master node
* Get the master shell scripts
```
$ wget -O master-start.sh https://raw.githubusercontent.com/vardhanv/cosbench_ng/master/master-start.sh
$ chmod +x ./master-start.sh
$ ./master-start.sh --help
```


### Optional Step - Only required if you need more workers
* find a seperate linux box/vm
* Get the slave shell scripts
```
$ wget -O slave-start.sh https://raw.githubusercontent.com/vardhanv/cosbench_ng/master/slave-start.sh
$ chmod +x ./slave-start.sh
$ ./slave-start.sh --help
```

## More Details
* Master Container Available at: docker pull vardhanv/cosbench_ng
* Slave Container Available at: docker pull vardhanv/cosbench_ng-slave

## Mac OS-X Known Issue
* Running both containers on the same OS-X host does not work, because of a known issue with UDP connection between two containers on docker for mac
