#!/bin/sh
# 에뮬레이터 시연 흐름. 먼저 adb forward tcp:8088 tcp:8088 과 Virtual Console heartbeat가 필요하다.
A=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
W=${WAIT:-4}
vc(){ curl -s -m 5 -X POST "http://127.0.0.1:8088$1" >/dev/null; }
vc "/vehicle/body?door_rl=false"; vc "/vehicle/body?door_rr=false"; vc "/vehicle/gear?gear=Park"
vc "/vehicle/seat?kind=occupied&area=rear_left&value=true"; vc "/vehicle/seat?kind=occupied&area=rear_right&value=true"
$A shell am force-stop ai.ridingrelay.app; $A logcat -c
$A shell am start -n ai.ridingrelay.app/.MainActivity >/dev/null; sleep 8
$A shell input tap 1385 1204; sleep $W            # 출발
vc "/vehicle/gear?gear=Drive"; sleep $W
vc "/vehicle/gear?gear=Park"; sleep 2             # 영어학원 도착
vc "/vehicle/body?door_rl=true"; sleep 2
vc "/vehicle/seat?kind=occupied&area=rear_left&value=false"; sleep $W
vc "/vehicle/body?door_rl=false"; sleep 1
vc "/vehicle/gear?gear=Drive"; sleep $W
vc "/vehicle/gear?gear=Park"; sleep 2             # 수학학원 도착
vc "/vehicle/body?door_rr=true"; sleep 2
vc "/vehicle/seat?kind=occupied&area=rear_right&value=false"; sleep $W
vc "/vehicle/body?door_rr=false"
$A logcat -d -s 'RidingRelay:V' | cut -c32-200 | grep -E "drop confirmed|request route|speak|navi"
