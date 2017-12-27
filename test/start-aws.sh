#!/bin/bash 
set -euo pipefail
IFS=$'\n\t'


read -e -p "Have you already done an ssh-add for your pem file? (y/n): " SSH_ADD

if [ ! $SSH_ADD == 'y' ]; then
   echo "First do an ssh-add, otherwise docker setup will fail"
   exit 1
fi


echo "logs in /tmp/aws-env-startup.log"
echo --------- `Date` -------- >> /tmp/aws-env-startup.log
echo "Spinning up instances"
aws --region us-west-2 ec2 run-instances --image-id ami-0def3275 --count 3 --instance-type t2.micro --key-name vish-cosbench --security-groups vish-cosbench-sg 1>/dev/null

echo "Waiting 30 seconds for instances to come up..."
sleep 30

if [ `aws --region us-west-2 ec2 describe-instances --filters "Name=instance-state-name, Values=pending" | wc -l` -eq 3 ]; then
    echo "All instances are running"
else
    echo "All instances were not started. cleaning up any that did get started and exiting.."
    aws --region us-west-2 ec2 describe-instances | grep InstanceId | cut -d'"' -f 4 | xargs aws --region us-west-2 ec2 terminate-instances --instance-ids  1>/dev/null
    exit 1
fi

aws --region us-west-2 ec2 describe-instances --filters "Name=instance-state-name, Values=running" | grep InstanceId | cut -d '"' -f 4 > /tmp/all-instance-ids 

# Get a list of private ip address and public dns names
echo "Finding IP address and dns names of instances..."
> /tmp/all-hosts
for i in `cat /tmp/all-instance-ids`
do
   aws --region us-west-2 ec2 describe-instances --instance-ids $i | grep -Ew "PublicDnsName|PrivateIpAddress" | tr -s ' ' | cut -d '"' -f4 | sort | uniq | tr -s '\n' ',' >> /tmp/all-hosts
   echo >> /tmp/all-hosts
done

cat /tmp/all-hosts | tail -n +2 > /tmp/slave-hosts
cat /tmp/all-hosts | head -n 1  > /tmp/master-host

cat /tmp/all-hosts   | cut -d ',' -f2 >  /tmp/all-hosts-dns
cat /tmp/slave-hosts | cut -d ',' -f2 > ./slave-hosts-dns
cat /tmp/master-host | cut -d ',' -f2 > ./master-host-dns

echo "Installing docker on all the hosts..."
pssh -o /tmp -h /tmp/all-hosts-dns -i -I  << EOF 1>>/tmp/aws-env-startup.log
export DEBIAN_FRONTEND=noninteractive
DEBIAN_FRONTEND=noninteractive sudo apt-get update
DEBIAN_FRONTEND=noninteractive sudo apt-get -y install docker.io
sudo usermod -aG docker \$USER
EOF


echo "Downloading startup scripts"
pssh -o /tmp -h ./slave-hosts-dns -i -I << EOF 1>>/tmp/aws-env-startup.log
  wget -O slave-start.sh https://raw.githubusercontent.com/vardhanv/cosbench_ng/master/slave-start.sh
  chmod +x ./slave-start.sh
EOF


pssh -o /tmp -h ./master-host-dns -i -I << EOF 1>>/tmp/aws-env-startup.log
  wget -O master-start.sh https://raw.githubusercontent.com/vardhanv/cosbench_ng/master/master-start.sh
  chmod +x ./master-start.sh
EOF


echo "Running ./slave-start.sh --configure on all slaves"
for i in `cat /tmp/slave-hosts`
do
  ip=`echo $i | cut -d ',' -f1`
  dest=`echo $i | cut -d ',' -f2`

  pssh -o /tmp -H $dest -i -I << EOF 1>>/tmp/aws-env-startup.log
     cat << E > .cosbench_ng
     export HOST_IP_ADDR=$ip
     export HOST_PORT_NO=23456
E
EOF
done


echo "Instance dns names available in ./slave-hosts-dns and ./master-host-dns"
echo "Environment setup. Master Private IP:" `cat /tmp/master-host | cut -d ',' -f1`, " Public DNS: " `cat ./master-host-dns`

echo "All instances in this region will be shut down in 20 minutes"
(sleep 1200; aws --region us-west-2 ec2 describe-instances | grep InstanceId | cut -d'"' -f 4 | xargs aws --region us-west-2 ec2 terminate-instances --instance-ids) &
