 #!/bin/bash


: "${HOST_PORT_NO:?shell variable needs to be set}"

if [ -z ${HOST_IP_ADDR} ]; then 
    echo "HOST_IP_ADDR needs to be set"; 
    exit 1
else 
    echo "Using HOST_IP_ADDR: $HOST_IP_ADDR"; 
fi

if [ -z ${HOST_PORT_NO} ]; then 
    echo "HOST_PORT_NO needs to be set"; 
    exit 1
else 
    echo "Using HOST_PORT_NO: $HOST_PORT_NO"; 
fi


#docker pull vardhanv/cosbench_ng-slave

docker run --shm-size 1G -v /tmp/t:/tmp -e HOST_PORT_NO -e HOST_IP_ADDR -p ${HOST_PORT_NO}:${HOST_PORT_NO}/udp  vardhanv/cosbench_ng-slave "$@"
