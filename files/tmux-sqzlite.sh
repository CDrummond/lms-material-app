#!/data/data/com.termux/files/usr/bin/bash -f

IFS="
"

if [ "`ps -eaf | grep squeezelite | grep -v grep | wc -l`" -eq "0" ] ; then
    /data/data/com.termux/files/usr/bin/squeezelite -M SqueezeLiteAndroid -C 5 $*
fi
