#!/bin/bash
set -euo pipefail
IFS=$'\n\t'



 create_config_file() {
    read -e -p "Enter your s3 target (e.g https://xyz.com:8082): " MY_TARGET
    read -ep "Enter the S3 Access Id: "  AWS_ACCESS_KEY_ID
    read -ep "Enter the S3 Secret Key: " AWS_SECRET_ACCESS_KEY
    read -ep "Enter IP Address of the master: " HOST_IP_ADDR

    echo "All your details are being saved in file .cosbench_ng"

    echo >> .cosbench_ng
    chmod +x .cosbench_ng

    echo "#New Configuration: `date`" >> .cosbench_ng
    echo "export MY_TARGET=$MY_TARGET"  >> .cosbench_ng
    echo "export AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID"  >> .cosbench_ng
    echo "export AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY"  >> .cosbench_ng
    echo "export HOST_IP_ADDR=$HOST_IP_ADDR"  >> .cosbench_ng
 
 }


print_help() {
   echo "First $0 --configure : Configure your environment"
   echo "then  $0 --help      : Help. Note, the endpoint option (-e) is not required" 
   echo "This is a script that runs the underlying docker container"
}


if [ $# -eq 0 ]; then
   print_help
   exit 1
elif [ "$1" == "--configure" ]; then
   create_config_file
   exit 0 
elif [ ! -f ./.cosbench_ng ]; then
   print_help
   exit 1
fi


. ./.cosbench_ng

if [ -z ${HOST_IP_ADDR} ]; then 
    echo "HOST_IP_ADDR needs to be set"; 
    echo "run: $0 --configure to configure your environment first"
    exit 1
fi

if [ -z ${AWS_ACCESS_KEY_ID} ]; then 
    echo "AWS_ACCESS_KEY_ID needs to be set"; 
    echo "run: $0 --configure to configure your environment first"
    exit 1
fi

if [ -z ${AWS_SECRET_ACCESS_KEY} ]; then 
    echo "AWS_SECRET_ACCESS_KEY needs to be set"; 
    echo "run: $0 --configure to configure your environment first"
    exit 1
fi

if [ -z ${MY_TARGET} ]; then 
    echo "MY_TARGET needs to be set"; 
    echo "run: $0 --configure to configure your environment first"
    exit 1
fi

echo "Using AWS_ACCESS_KEY_ID     : $AWS_ACCESS_KEY_ID"; 
echo "Using AWS_SECRET_ACCESS_KEY : ${AWS_SECRET_ACCESS_KEY:0:1}***********"; 
echo "Using MY_TARGET             : $MY_TARGET"; 
echo "Using HOST_IP_ADDR          : $HOST_IP_ADDR"; 


# -Daeron.* are to reduce aeron memory footprint so we can deploy more slaves. Not required if the host machines have more RAM
docker run --shm-size 2g -v /tmp:/tmp -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e HOST_IP_ADDR -p 25521:25521/udp vardhanv/cosbench_ng:0.9 -Daeron.ipc.term.buffer.length=33554432 -Daeron.term.buffer.length=8388608 -e $MY_TARGET "$@"

