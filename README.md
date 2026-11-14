# ROADIE

운전을 이야기로 만드는 반려차 드라이브 앱. 코스 위의 장면 포인트에 맞춰 음악 클라이맥스를 터뜨립니다.

Pleos Connect 차량 환경용 앱입니다. 반려차가 말을 걸고, 코스의 장면 포인트(터널 출구, 해안 시야, 고갯마루 등)에 도착하는 순간에 맞춰 음악이 고조됩니다. 도착하면 오늘 지나온 장면을 카드로 정리해 보여줍니다.

## 주요 기능
- 반려차 안내: 홈 인사, 장면 직전 한마디, 도착 한마디를 음성(TTS)으로 말합니다. 대사는 상황별 템플릿입니다.
- 드라이브 코스: 장면 포인트가 표시된 코스 3개(바다, 고갯길, 야경). 코스 데이터는 연출용이며 실제 도로와 다를 수 있습니다.
- 드라이브 OST: 곡이 5개 레이어(pad, bass, arp, lead, drums)로 나뉘어 있고, 장면까지의 예상 도착 시간에 따라 레이어가 켜지고 꺼집니다. 도착 시각을 마디 경계에 맞춰 riser와 impact를 넣어 클라이맥스를 만듭니다. 정체로 늦어지면 접근 상태에서 기다립니다.
- 도착 카드: 달린 거리, 걸린 시간, 지나온 장면과 시각, 반려차 한마디, 누적 기록(총 거리, 횟수). 기록은 기기 안에만 저장됩니다.
- 시뮬레이션 주행: 에뮬레이터는 실제로 달릴 수 없어서, 코스의 구간 속도로 가짜 속도와 위치를 만들어 재생합니다(1x, 10x, 20x). 장면 앞 약 1km는 자동으로 느려집니다.

## 사용한 Pleos Connect SDK
- NaviHelper 3.0.5: 출발할 때 코스 목적지로 경로를 요청하고, 종료할 때 취소합니다. 요청하면 차량 내비 앱이 앞으로 나오기 때문에 앱이 화면을 다시 가져옵니다.
- TextToSpeech 3.3.1: 반려차 음성(한국어). Pleos TTS가 안 되는 환경에서는 안드로이드 기본 TTS로 넘어갑니다.
- 차량 속도는 AAOS Car API(`android.car`, `CarPropertyManager`)로 읽습니다. 시뮬레이션 주행을 끄면 이 값을 사용합니다.

필요한 권한: `android.car.permission.CAR_SPEED`, `pleos.car.permission.NAVI_ROUTE`, `pleos.car.permission.NAVI_ROUTE_SEARCH`, `pleos.car.permission.TTS_SERVICE`

## 개발·테스트 환경
- Android Studio Rabbit 2 (2026.2.2)
- Pleos Connect Emulator v2.1.0 (Pleos Connect v2.1.0 APIs, Android 14 / API 34), Cold boot, RAM 4GB
- 화면 규격: 2560 x 1440, density 160 (앱 영역은 오른쪽 약 1680 x 1210)
- Kotlin, minSdk 28, targetSdk 34, compileSdk 37, AGP 9.4.1, Gradle 9.9.1

## 실행 방법
1. Android Studio에서 Pleos Connect v2.1.0 AVD를 만들고, 기본 부팅(Default boot)을 Cold로 설정합니다.
2. 에뮬레이터를 실행합니다. 부팅이 끝나면 아래 둘 중 하나로 설치합니다.
   - 제출용 APK: `adb install apk/DeepTactics_ROADIE_release.apk`
   - 소스에서: 이 저장소를 Android Studio로 열고 Gradle 동기화 후 `app`을 실행합니다.
3. 앱을 열면 홈 화면에서 코스를 고르고 `출발`을 누릅니다. 시뮬레이션 주행 스위치는 기본으로 켜져 있고, 옆 버튼으로 배속(1x, 10x, 20x)을 바꿉니다.
4. 시뮬레이션 주행을 끄면 실제 차량 속도를 읽습니다. 이때 위치 정보(CAR_SPEED) 권한 허용 창이 뜹니다.

APK는 `apk/` 폴더에 있습니다: `apk/DeepTactics_ROADIE_release.apk`

## 알아둘 점
- 코스 한 번(바다 코스)은 20배속으로도 약 5분 걸립니다. 장면마다 대사 후 60초의 빌드업을 두었기 때문입니다.
- 실제 차량 속도 입력은 에뮬레이터에서 값(0 km/h)이 들어오는 것까지 확인했습니다.
- 기본 안드로이드 TTS는 에뮬레이터에 음성 엔진이 없어서 동작을 확인하지 못했습니다.

## 폴더 구조
- `app/` 안드로이드 앱 소스
- `assets_src/music/` 음악 루프 원본(wav)
- `tools/make_loops.py` 음악 루프 생성 스크립트 (numpy 필요)
- `apk/` 제출용 release APK

## 음원
앱의 음원은 `tools/make_loops.py`로 합성한 곡이며 외부 음원은 사용하지 않았습니다.
