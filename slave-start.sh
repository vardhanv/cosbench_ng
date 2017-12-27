#!/bin/bash
set -euo pipefail
IFS=$'\n\t'




create_config_file() {
    read -ep "Enter IP Address of the host running cosbench_ng slave: " HOST_IP_ADDR
    read -ep "Enter an unused port number on this host: " HOST_PORT_NO

    echo "All your details are being saved in file .cosbench_ng"

    echo >> .cosbench_ng
    chmod +x .cosbench_ng

    echo "#New Slave Configuration: `date`"   >> .cosbench_ng
    echo "export HOST_IP_ADDR=$HOST_IP_ADDR"  >> .cosbench_ng
    echo "export HOST_PORT_NO=$HOST_PORT_NO"  >> .cosbench_ng

}


print_help() {
   echo "First $0 --configure to configure your environment"
   echo "then  $0 --help to see help instructions for cosbench_ng slave"
   echo "Be aware that this is only a wraper script that helps setup the environment and runs the underlying docker container"
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
else 
    echo "Using HOST_IP_ADDR: $HOST_IP_ADDR"; 
fi

if [ -z ${HOST_PORT_NO} ]; then 
    echo "HOST_PORT_NO needs to be set"; 
    echo "run: $0 --configure to configure your environment first"
    exit 1
else 
    echo "Using HOST_PORT_NO: $HOST_PORT_NO"; 
fi

while true
do
   docker run --shm-size 1G -v /tmp:/tmp -e HOST_PORT_NO -e HOST_IP_ADDR -p ${HOST_PORT_NO}:${HOST_PORT_NO}/udp  vardhanv/cosbench_ng-slave:0.9 "$@"
done
