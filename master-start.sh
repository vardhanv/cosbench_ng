 #!/bin/bash

docker pull vardhanv/cosbench_ng

if [ -z ${HOST_IP_ADDR} ]; then 
    echo "HOST_IP_ADDR needs to be set"; 
    exit 1
else 
    echo "Using HOST_IP_ADDR: $HOST_IP_ADDR"; 
fi

if [ -z ${AWS_ACCESS_KEY_ID} ]; then 
    echo "AWS_ACCESS_KEY_ID needs to be set"; 
    exit 1
else 
    echo "Using AWS_ACCESS_KEY_ID: $AWS_ACCESS_KEY_ID"; 
fi


if [ -z ${AWS_SECRET_ACCESS_KEY} ]; then 
    echo "AWS_SECRET_ACCESS_KEY needs to be set"; 
    exit 1
else 
    echo "Using AWS_SECRET_ACCESS_KEY: $AWS_SECRET_ACCESS_KEY"; 
fi

docker run --shm-size 1G -v /tmp:/tmp -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e HOST_IP_ADDR -p 25521:25521/udp vardhanv/cosbench_ng "$@"
