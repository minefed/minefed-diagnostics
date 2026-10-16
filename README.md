# Minefed Diagnostics

Minefed 서버(Minecraft 1.20.4 / Fabric)에서 **청크 로딩이 멈추거나 서버가 멈추는(hang) 현상의 원인을 기록**하는
서버 전용 모드다. 게임 동작은 바꾸지 않는다. 문제가 생긴 순간의 증거를 남겨 원인 모드나 환경을 특정하는 것이 목적이다.

## 무엇을 잡아내는가

증상이 같아 보여도 원인은 서로 다른 네 경로 중 하나다. 모드는 각각을 따로 감지하고 판정을 남긴다.

| 감지기 | 상황 | 남기는 것 |
| --- | --- | --- |
| 서버 스레드 정지 | 서버 틱이 `stall.thresholdMs`(1초) 이상 끝나지 않음 | 50ms 간격 스택 샘플 프로파일, 원인 모드 순위, 락 소유 스레드, 5초·40초 시점 전체 스레드 덤프 |
| 서버 스레드의 동기 청크 로드 | 서버 스레드가 준비되지 않은 청크를 기다리며 막힘 | 요청한 코드(모드)별 횟수·누적·최대 시간, 청크 좌표, 첫 호출 스택 |
| 다른 스레드의 getChunk | MTR·BlueMap 등 다른 스레드가 `getChunk`로 서버 스레드를 기다림 | 호출자별 통계. 서버 스레드가 그 스레드의 락을 기다리면 **교착 상태**로 표시 |
| 청크 파이프라인 정지 | 틱은 정상인데 청크 읽기·생성·완성 단계가 `chunkTask.stallMs`(20초) 넘게 끝나지 않음 | 멈춘 청크 목록과 holder 상태, 우선순위 큐·리전 IO 큐·워커 풀 상태, 스레드 덤프 |
| 청크 전송 정지 | 플레이어에게 보낼 청크가 남았는데 10초간 줄지 않음 | `WAITING_FOR_CLIENT_ACK`(클라이언트·프록시·네트워크 문제) 또는 `CHUNKS_NOT_READY`(서버 파이프라인 문제) 판정 |
| JVM 전체 정지 | 감시 스레드조차 1초 이상 실행되지 못함 | GC 통계. 게임 코드가 아니라 GC·스왑·컨테이너 CPU 제한 문제임을 구분 |

바닐라 서버 감시자는 틱이 60초를 넘으면 서버를 강제 종료한다. 이 모드는 그 전에(5초, 40초) 진행 중 보고서를 디스크에
써 두므로 서버가 강제 종료되어도 증거가 남는다.

## 결과 확인

- 로그: `MinefedDiagnostics` 로거의 WARN 한 줄 요약. 예: `Server thread stalled for 8123ms (tick 51234); culprit: mtr; report ...`
- 보고서: `logs/minefed-diagnostics/<시각>_<종류>.txt` (`server-stall`, `chunk-pipeline-stall`, `chunk-send-stall`, `snapshot`).
  최신 `report.retain`개(기본 200)만 남긴다.
- 요약: 10분마다 `logs/minefed-diagnostics/summary.log`에 누적 통계를 추가한다.
- 스택의 Minecraft 이름은 운영 환경 그대로 intermediary(`net.minecraft.class_3215.method_12121`)다.
  Yarn `1.20.4+build.3` 매핑으로 바꿔 읽는다.

## 명령 (권한 4, 콘솔 가능)

| 명령 | 설명 |
| --- | --- |
| `/mfdiag` 또는 `/mfdiag status` | 틱 상태, 정지·동결 횟수, 진행 중 청크 작업, 훅 동작 여부 |
| `/mfdiag chunks` | 월드별 청크 큐 상태와 가장 오래된 청크 작업 |
| `/mfdiag players` | 플레이어별 청크 전송 대기열과 미확인 배치 수 |
| `/mfdiag callers` | 동기 청크 로드·다른 스레드 getChunk 상위 호출자 |
| `/mfdiag dump` | 즉시 전체 스냅샷 보고서 작성 |
| `/mfdiag reset` | 누적 통계 초기화 |
| `/mfdiag selftest stall <ms>` | 서버 스레드를 일부러 멈춰 감지·보고서 동작 확인(최대 30초) |

`Hooks fired`에 `-`로 표시된 훅은 아직 한 번도 호출되지 않은 것이다. 서버가 청크를 로드한 뒤에도
`loadChunk-`처럼 남아 있으면 다른 모드와의 충돌로 주입이 빠진 것이므로 보고서 해석에 주의한다.

## 설정

첫 실행 시 `config/minefed-diagnostics.properties`가 기본값으로 생성된다. 변경 후 서버를 재시작한다.

| 키 | 기본값 | 의미 |
| --- | ---: | --- |
| `stall.thresholdMs` | 1000 | 이 시간 동안 틱이 끝나지 않으면 샘플링 시작 |
| `stall.reportMinMs` | 3000 | 이보다 짧은 정지는 횟수만 센다 |
| `stall.sampleIntervalMs` | 50 | 정지 중 스택 샘플 간격 |
| `stall.firstDumpMs` / `stall.secondDumpMs` | 5000 / 40000 | 진행 중 보고서와 전체 스레드 덤프 시점 |
| `syncLoad.warnMs` | 100 | 서버 스레드 동기 청크 대기 기록 기준 |
| `offThread.warnMs` | 500 | 다른 스레드 getChunk 대기 기록 기준 |
| `chunkTask.stallMs` | 20000 | 청크 작업 정지 판정 기준 |
| `chunkSend.stallMs` | 10000 | 청크 전송 정지 판정 기준 |
| `chunkSend.resetStuckAcks` | false | 켜면 클라이언트 확인 응답이 `chunkSend.resetAfterMs`(30초) 넘게 오지 않을 때 미확인 배치 수를 0으로 되돌려 전송을 재개한다. 기본은 기록만 한다. |
| `jvmFreeze.warnMs` | 1000 | JVM 전체 정지 판정 기준 |
| `report.cooldownMs` | 300000 | 같은 종류 청크 보고서의 최소 간격 |

## 부하

서버 스레드에서 추가되는 일은 `getChunk` 진입 시 스레드 비교와 요청 좌표 기록(할당 없음), 틱마다 시간 기록,
1초마다 플레이어별 전송 상태 확인뿐이다. 스택 샘플·덤프·파일 쓰기는 별도 데몬 스레드
(`Minefed-Diagnostics-Watchdog`, `Minefed-Diagnostics-Reporter`)에서 수행한다. 모든 주입은 실패해도 서버를
멈추지 않도록 `require = 0`이며, 훅 내부 오류는 기록 후 무시한다.

## 호환성

운영 서버팩 20261014150849의 모드 70개(Lithium의 `getChunk` 덮어쓰기, ModernFix의 청크 스케줄링 변경,
Carpet, Chunky 포함)와 함께 `tools/mixin-audit`를 모든 인젝터 필수 조건으로 실행해 통과를 확인했다.

## 빌드

```sh
./gradlew build
```

Windows에서 사용자 경로에 한글이 있으면 Gradle 테스트 워커가 시작되지 않는다. 이때는
`./gradlew build "-Dorg.gradle.jvmargs=-Xmx1G"`로 데몬의 `file.encoding` 지정을 빼고 실행한다.
