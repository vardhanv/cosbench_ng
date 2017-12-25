#!/bin/bash -x
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


if [ $# -eq 0 ]; then
   echo "Run $0 --configure to configure your environment"
   echo "Run $0 --help to see help instructions for cosbench_ng"
   echo "Be aware that this is only a wraper script that helps setup the environment and runs the underlying docker container"
   exit 1
elif [ "$1" == "--configure" ]; then
   create_config_file
   exit 0 
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
echo "Using AWS_SECRET_ACCESS_KEY : $AWS_SECRET_ACCESS_KEY"; 
echo "Using MY_TARGET             : $MY_TARGET"; 
echo "Using HOST_IP_ADDR          : $HOST_IP_ADDR"; 

docker run --shm-size 1G -v /tmp:/tmp -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e HOST_IP_ADDR -p 25521:25521/udp vardhanv/cosbench_ng -e $MY_TARGET "$@"

