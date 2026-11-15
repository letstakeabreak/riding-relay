# Riding Relay (라이딩 릴레이)

아이를 학원 여러 곳에 차로 데려다줄 때, P단과 뒷문과 뒷좌석 신호로 하차를 확인하고 내비 목적지를 다음 학원으로 바꿔 주는 Pleos Connect 차량용 앱입니다.

## 동작
1. 뒷좌석 점유 신호가 켜지면 탑승을 표시합니다.
2. 출발을 누르면 첫 학원으로 경로를 요청합니다.
3. P단으로 바꾼 뒤 그 자리의 뒷문이 열리고 좌석 점유가 꺼지면 하차로 확정합니다. 음성 안내를 요청하고 내비 목적지를 다음 학원으로 바꿉니다.
4. 모두 내리면 집으로 안내합니다. 하차 시각이 일정표에 남습니다.

주행 중 화면에는 다음 목적지와 탑승 상태 두 줄만 보이고, 일정표는 P단에서만 보입니다.

## 화면
Pleos Connect UI Design Guide의 색상 토큰, 글자 크기 체계, 버튼 상호작용을 따르고 주간과 야간 테마를 지원합니다. 적용 내용은 `DESIGN.md`에 있습니다.

## 실행 방법
```
export JAVA_HOME=<JDK 17 경로>
./gradlew testDebugUnitTest assembleRelease
adb install -r apk/라이딩릴레이_release.apk
adb shell am start -n ai.ridingrelay.app/.MainActivity
```
시연 흐름은 `tools/demo.sh`로 한 번에 돌릴 수 있습니다. 먼저 `adb forward tcp:8088 tcp:8088`을 실행하고, Virtual Console(http://127.0.0.1:8088/)을 브라우저로 열어 두거나 `/browser/session`에 1초마다 POST를 보내야 신호 입력이 활성화됩니다.

## 테스트 환경과 필요한 설정
- Pleos Connect Emulator v2.1.0, arm64 이미지, 화면 2560x1440, RAM 4096MB, cold boot
- Kotlin 1.9.25, AGP 8.5.2, Gradle 8.9, JDK 17, compileSdk 34, minSdk 28
- SDK: Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1 (https://nexus-playground.pleos.ai/repository/maven-releases/)
- 권한: `pleos.car.permission.CAR_DRIVE`, `CAR_DOORS`, `CAR_SEATS`, `TTS_SERVICE`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`
- 주행 중 실행을 위해 액티비티에 `distractionOptimized` 메타데이터를 선언했습니다.
- API 키나 서버는 쓰지 않습니다.

## APK 파일 위치
`apk/라이딩릴레이_release.apk` (release 빌드, 시연용으로 debug 키 서명)

## 검증 기록 (2026-11-15, 에뮬레이터)
- 단위 테스트 19개 통과(`RelayEngineTest`): 주행 중 점유 꺼짐 무시, 문 미개방 무시, 이전 정차의 문 열림 미반영, 중복 확정 방지 등
- `tools/demo.sh` 5회 연속 통과: 두 아이 하차 확정, 목적지 영어학원, 수학학원, 집 순서로 변경
- 시연 영상은 release APK로 녹화했습니다.

## 알려진 한계
- 좌석 점유 값: Emulator v2.1.0과 Vehicle SDK 2.3.2 조합에서 SDK가 돌려주는 값이 Virtual Console 표시와 반대입니다. 차량 HAL은 1(빈자리), 2(착석)를 보내는데 SDK는 1일 때 true를 돌려줍니다. `MainActivity.SEAT_VALUE_INVERTED`로 보정했고 실제 차량에서 다시 확인해야 합니다. 초기값 0(알 수 없음)은 착석으로 읽힙니다.
- 음성: TextToSpeech SDK 초기화와 speak 호출은 되지만 에뮬레이터에서 `ON_DEVICE_ENGINE_ERROR`가 돌아와 소리는 확인하지 못했습니다.
- 내비가 앞에 있는 동안 신호를 받으려고 포그라운드 서비스를 씁니다. 없으면 시스템이 앱을 멈춰 신호가 밀립니다.
- 일정(아이, 학원, 좌표)은 시연용 고정값입니다. 학원 도착은 P단으로만 판단합니다.
- 실제 차량에서는 검증하지 않았습니다.
