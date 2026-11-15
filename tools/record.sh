#!/bin/sh
# 시연 영상 녹화. 결과: /sdcard/relay.mp4 와 자막용 시각 기록(표준 출력).
A=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
vc(){ curl -s -m 5 -X POST "http://127.0.0.1:8088$1" >/dev/null; }
app(){ $A shell am start -n ai.ridingrelay.app/.MainActivity >/dev/null 2>&1; }
vc "/vehicle/body?door_rl=false"; vc "/vehicle/body?door_rr=false"; vc "/vehicle/gear?gear=Park"
vc "/vehicle/seat?kind=occupied&area=rear_left&value=false"; vc "/vehicle/seat?kind=occupied&area=rear_right&value=false"
$A shell am force-stop ai.ridingrelay.app; $A logcat -c; app; sleep 8
$A shell screenrecord --size 1280x720 --bit-rate 6000000 --time-limit 150 /sdcard/relay.mp4 &
sleep 2; T0=$(date +%s)
mark(){ echo "$(( $(date +%s) - T0 )) $1"; }
mark "start_wait"; sleep 4
vc "/vehicle/seat?kind=occupied&area=rear_left&value=true"; mark "board_left"; sleep 5
vc "/vehicle/seat?kind=occupied&area=rear_right&value=true"; mark "board_right"; sleep 5
$A shell input tap 1385 1204; mark "tap_start"; sleep 8
vc "/vehicle/gear?gear=Drive"; mark "drive1"; sleep 3
app; mark "app_driving"; sleep 5
vc "/vehicle/body?door_rr=true"; mark "door_warning"; sleep 6     # 주행 중 뒷문 열림
vc "/vehicle/body?door_rr=false"; sleep 2
vc "/vehicle/gear?gear=Park"; mark "park1"; sleep 6                # 도착, 전달 메모
vc "/vehicle/body?door_rl=true"; sleep 2
vc "/vehicle/body?door_rl=false"; mark "asking"; sleep 6           # 문을 닫았는데 그대로 앉아 있음
vc "/vehicle/gear?gear=Drive"; mark "still_warning"; sleep 7       # 그대로 출발
vc "/vehicle/gear?gear=Park"; mark "park1_again"; sleep 4
vc "/vehicle/body?door_rl=true"; sleep 3
vc "/vehicle/seat?kind=occupied&area=rear_left&value=false"; mark "drop1"; sleep 8
app; mark "app_drop1"; sleep 6
vc "/vehicle/body?door_rl=false"; sleep 2
vc "/vehicle/gear?gear=Drive"; mark "drive2"; sleep 5
vc "/vehicle/gear?gear=Park"; mark "park2"; sleep 5
vc "/vehicle/body?door_rr=true"; sleep 3
vc "/vehicle/seat?kind=occupied&area=rear_right&value=false"; mark "drop2"; sleep 8
app; mark "app_drop2"; sleep 7
vc "/vehicle/body?door_rr=false"; mark "end"; sleep 2
$A shell pkill -INT screenrecord; sleep 3
