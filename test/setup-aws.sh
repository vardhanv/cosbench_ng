#!/bin/bash  -x
set -euo pipefail
IFS=$'\n\t'


cat slave-hosts master-host  > all-hosts

cat << EOF | pssh -h all-hosts -i -I 
DEBIAN_FRONTEND=noninteractive nohup sudo apt-get update
DEBIAN_FRONTEND=noninteractive nohup sudo apt-get -y install docker.io
sudo usermod -aG docker \$USER
EOF


pssh -h slave-hosts -i -I << EOF
  wget -O slave-start.sh https://raw.githubusercontent.com/vardhanv/cosbench_ng/master/slave-start.sh
  chmod +x ./slave-start.sh
EOF


pssh -h master-host -i -I << EOF
  wget -O master-start.sh https://raw.githubusercontent.com/vardhanv/cosbench_ng/master/master-start.sh
  chmod +x ./master-start.sh
EOF

