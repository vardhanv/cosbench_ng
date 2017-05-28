 #!/bin/bash

: "${HOST_IP_ADDR:?shell variable needs to be set}"
: "${HOST_PORT_NO:?shell variable needs to be set}"

 docker run --shm-size 1G -v /tmp/t:/tmp -e HOST_PORT_NO -e HOST_IP_ADDR -p ${HOST_PORT_NO}:${HOST_PORT_NO}/udp cosbench_ng-slave:0.8 "$@"
