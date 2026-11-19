# 🎁 우리 기프티콘

연인과 함께 받은 기프티콘을 한곳에 모아 두는 앱입니다.

- **웹:** https://cheesebyte99.github.io/our-gifticons/
- **안드로이드 앱:** [Releases](https://github.com/cheesebyte99/our-gifticons/releases/latest)에서 `our-gifticons.apk`를 받아 설치해요.

## 할 수 있는 것

- **기프티콘 모으기:** 캡처한 기프티콘 이미지를 올려요. 한 번에 여러 장도 돼요.
  안드로이드 앱에서는 카카오톡 등에서 **공유 → 우리 기프티콘**으로 바로 넣을 수 있어요.
- **자동으로 채우기:** 이미지의 글자를 읽어 **이름·브랜드·유효기간**을 채워요. 틀린 곳만 고치면 돼요.
  - 글자 읽기는 휴대폰 안에서 [tesseract.js](https://github.com/naptha/tesseract.js)로 해요. 이미지를 다른 서버로 보내지 않아요.
  - 처음 한 번은 글자 읽기 도구(약 8MB)를 받느라 조금 걸려요.
- **사용 완료 체크:** 카드의 ○를 누르거나 크게 본 화면에서 「✓ 사용 완료」를 누르면 이미지가 **흑백**으로 바뀌어요.
  「사용완료」 도장도 찍히고 「사용 완료」 탭으로 옮겨져요. 잘못 눌렀으면 「되돌리기」를 누르면 돼요.
- **D-day:** 유효기간이 가까운 것부터 보여요. 3일 이내는 빨간색, 7일 이내는 주황색으로 표시돼요.
- **함께 쓰기:** 초대 링크를 보내면 둘이 같은 기프티콘함을 실시간으로 봐요.
- **알림** (설정 → 🔔 알림)
  - 유효기간 알림은 당일, 하루 전, 3일 전, 일주일 전, 2주 전, 한 달 전 중에서 골라요. 알림 시각도 정할 수 있어요.
  - 상대가 기프티콘을 넣거나 썼을 때도 알려 줘요.
- **매장에서:** 쓰지 않은 기프티콘 원본은 휴대폰에 미리 받아 둬요. 인터넷이 약해도 바코드를 열 수 있어요.

## 안전하게

기프티콘은 바코드만 있으면 남이 쓸 수 있어요. 그래서 사진첩보다 단단히 막아 두었어요.

- 이미지는 공개 주소가 생기는 사진 저장소가 아니라 **Firebase 안에만** 저장해요.
- **초대 코드로 들어온 기기만** 기프티콘을 읽고 쓸 수 있어요. 서버 규칙이 확인해요.
  - 초대 코드는 16글자로, 방 번호 8글자 + 비밀 8글자예요.
  - 방 번호만 알아서는 들어올 수 없어요.
- 저장소 코드가 공개되어 있어도 기프티콘은 보이지 않아요.

## 처음 한 번 설정 (관리자)

1. **Firebase 익명 로그인 켜기**
   - [Firebase 콘솔](https://console.firebase.google.com/project/jaeyeon-life/authentication/providers) → Authentication → 로그인 방법 → **익명** → 사용 설정 → 저장
   - 처음이면 「시작하기」를 먼저 눌러요.
2. **규칙·알림 서버 배포**
   - [our-firebase](https://github.com/cheesebyte99/our-firebase)의 `main`에 올라가면 자동으로 배포돼요.
   - 앱보다 **먼저** 배포해야 해요.
3. **웹 주소 켜기**
   - 이 저장소 → Settings → Pages → Source: **Deploy from a branch**
   - Branch: **main / (root)** → Save
4. **안드로이드 푸시 알림** (선택)
   - Firebase 콘솔 → 프로젝트 설정 → 내 앱 → 앱 추가 → Android를 골라요.
   - 패키지 이름은 `io.github.cheesebyte99.gifticons`로 적어요.
   - 받은 `google-services.json`을 이 저장소의 `android/app/` 폴더에 올려요.
   - 이 파일이 없으면 앱은 알림 없이 동작해요. 웹(크롬, 아이폰 홈 화면 앱)의 알림은 이 파일 없이도 돼요.

## 파일

| 파일 | 설명 |
| --- | --- |
| `index.html` | 웹앱 전체 (화면 · Firebase · 글자 읽기 · 알림) |
| `ocr-parse.js` | 읽은 글자에서 이름·브랜드·유효기간을 골라내는 규칙 |
| `test/ocr-parse.test.js` | 그 규칙의 시험 — `node --test test/*.test.js` |
| `manifest.webmanifest`, 아이콘들 | 홈 화면에 추가(PWA) |
| `firebase-messaging-sw.js` | 웹 푸시 알림 받기 |
| `android/` | 안드로이드 앱 (빌드할 때 웹 파일을 앱 안에 넣어요) |
| `.github/workflows/android-apk.yml` | 시험 → APK 빌드 → `main`이면 Releases에 올리기 |

Firestore 규칙과 알림 서버(`giftItemChanged`·`giftExpiryReminders`)는 [our-firebase](https://github.com/cheesebyte99/our-firebase)에 있어요.
