#!/bin/sh
cp /home/wisdomme/code/UltiTools/Home/target/UltiTools-Home-"$1".jar /home/wisdomme/mc/servers/1.19.2/plugins/UltiTools/plugins
screen -S spigot -p 0 -X stuff "reload\n"
