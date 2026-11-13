# [앱 이름]

[한 줄 소개]

Pleos Connect 차량 환경용 앱입니다. 정해진 코스를 달리면서, 경로 위의 장면 포인트에 맞춰 음악이 고조되도록 연출합니다.

## 주요 기능
- 반려차 안내: 출발, 도착 등 상황에 맞춰 음성으로 말을 겁니다.
- 드라이브 코스: 장면 포인트가 표시된 순환 코스를 안내합니다.
- 드라이브 OST: 속도와 장면 포인트까지의 거리에 따라 곡의 레이어가 바뀌고, 포인트에서 클라이맥스가 터집니다.
- 도착 카드: 달린 코스와 지나온 장면을 정리해 보여줍니다.

## 개발·테스트 환경
- Android Studio [버전]
- Pleos Connect Emulator [버전], 화면 규격 [해상도]
- Kotlin, minSdk 28, Gradle [버전]
- 사용한 SDK: Vehicle, NaviHelper, TextToSpeech

## 실행 방법
1. Android Studio에서 Pleos Connect 에뮬레이터 AVD를 만듭니다. 부팅 옵션은 Cold boot로 설정합니다.
2. 이 저장소를 열고 Gradle 동기화를 마칩니다.
3. 에뮬레이터를 실행한 뒤 `app` 모듈을 실행합니다.
4. 에뮬레이터는 실제 주행이 안 되므로 앱 안의 시뮬레이션 주행으로 코스를 재생해 볼 수 있습니다.

APK는 `apk/` 폴더에 있습니다: `apk/[팀명]_[앱이름]_release.apk`

## 폴더 구조
- `app/` 안드로이드 앱 소스
- `assets_src/music/` 음악 루프 원본(wav)
- `tools/make_loops.py` 음악 루프 생성 스크립트 (numpy 필요)
- `apk/` 제출용 release apk

## 음원
앱에 쓰인 음원은 모두 `tools/make_loops.py`로 직접 합성한 곡이며 외부 음원은 사용하지 않았습니다.
