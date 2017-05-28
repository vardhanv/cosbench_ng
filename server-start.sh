 #!/bin/bash

: "${HOST_IP_ADDR:?shell variable needs to be set}"
: "${AWS_ACCESS_KEY_ID:?shell variable needs to be set}"
: "${AWS_SECRET_ACCESS_KEY:?shell variable needs to be set}"

 docker run --shm-size 1G -v /tmp:/tmp -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e HOST_IP_ADDR -p 25521:25521/udp cosbench_ng:0.8 "$@"
