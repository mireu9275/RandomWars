# RandomWars

Paper 1.21.11 기반 랜덤무기전쟁 서버 (모드 없이, 리소스팩 + Blockbench 모델).

- 기획서: [docs/01-기획서.md](docs/01-기획서.md)
- MVP 계획: [docs/02-MVP계획.md](docs/02-MVP계획.md)

작업 브랜치는 `develop`입니다.

## 개발

```
plugin/        Kotlin Gradle 프로젝트 (RandomWars 플러그인)
resourcepack/  리소스팩 원본 (빌드 시 플러그인 jar 안에 pack.zip 으로 들어감)
blockbench/    .bbmodel 원본
server/        로컬 테스트 서버 (저장소 제외)
```

- 빌드: `plugin` 폴더에서 `gradlew.bat build` → `server/plugins/RandomWars.jar` 로 자동 복사
- 리소스팩: 플러그인이 내장 HTTP 서버(기본 포트 8163)로 내려주고 접속 시 필수 적용. 다른 PC에서 접속하려면 `plugins/RandomWars/config.yml` 의 `resource-pack.public-host` 를 서버 주소로 바꾼다.
- 관리 명령: `/rw version`, `/rw reload`, `/rw box give <플레이어> <일반|보급> [개수]`, `/rw box sim <일반|보급> [횟수]`, `/rw weapon list`, `/rw weapon give <플레이어> <무기 id>`
