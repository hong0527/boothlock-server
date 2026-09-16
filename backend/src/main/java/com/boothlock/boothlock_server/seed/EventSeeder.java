package com.boothlock.boothlock_server.seed;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.event.domain.EventMapEntity;
import com.boothlock.boothlock_server.event.repository.EventMapRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 행사 시딩 — 부스들·부스별 ADMIN 계정·약도 1건 (API 명세 §5 "시딩 산출물", 확정 필요 #8).
 * 복수 부스 파일럿에서 손님 홈 화면(E1·E2)이 빈 화면으로 뜨지 않게 하는 수단이다.
 *
 * <p><b>opt-in</b>: {@code boothlock.seed.enabled=true}일 때만 빈이 생긴다. 기본값이 꺼짐이라
 * 테스트 컨텍스트·평소 기동에서는 존재하지 않는다.
 *
 * <p><b>실행 시점</b>: ApplicationRunner는 컨텍스트 리프레시가 끝난 뒤 돈다 —
 * JPA EntityManagerFactory 초기화(ddl-auto=update 스키마 반영)가 그보다 앞이므로 테이블이 있는 상태에서 실행된다.
 * 실패하면 예외가 SpringApplication.run 밖으로 나가 프로세스가 기동에 실패한다.
 *
 * <p><b>멱등 규칙</b> — 기존 행은 절대 고치지 않는다:
 * <ul>
 *   <li>부스: 같은 이름이 1건 있으면 그 부스를 쓰고 값은 건드리지 않는다. 운영자가 O17로 옮긴 좌표나
 *       감사 로그를 거쳐 바꾼 계좌를 시더가 되돌리면 안 된다(시더의 계좌 덮어쓰기는 감사 로그를 우회한다).
 *       같은 이름이 2건 이상이면 어느 부스에 계정을 붙일지 정할 수 없어 실패한다.</li>
 *   <li>계정: 같은 loginId가 이미 있고 그 부스의 ADMIN이면 건너뛴다(비밀번호도 재설정하지 않는다 —
 *       재설정하면 pwdAt이 바뀌어 행사 중 로그인된 기기가 전부 튕긴다). 다른 부스·다른 역할이면 설정 오류로 실패한다.</li>
 *   <li>약도: 가장 최근 약도(id 기준, E2와 같은 기준)가 파일과 같으면 건너뛰고, 다르면 1건을 추가한다.
 *       약도는 운영자 API가 없어 파일이 유일한 원본이므로, 파일을 바꿨다는 것은 교체하겠다는 뜻이다.</li>
 * </ul>
 * 파일 검증은 DB에 손대기 전에 전부 끝내고, DB 반영은 한 트랜잭션이다 — 중간에 실패하면 아무것도 남지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "boothlock.seed", name = "enabled", havingValue = "true")
public class EventSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EventSeeder.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final String seedFile;
    private final Path eventUploadDir;
    private final Environment environment;
    private final BoothRepository boothRepository;
    private final StaffAccountRepository staffAccountRepository;
    private final EventMapRepository eventMapRepository;
    private final TransactionTemplate transactionTemplate;
    // 로그인(BoothAuthService)과 같은 인코더여야 시드 계정으로 로그인된다
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    public EventSeeder(@Value("${boothlock.seed.file:}") String seedFile,
                       // EventUploadWebConfig와 같은 키·기본값 — 서빙하는 폴더에서 파일을 찾아야 의미가 있다
                       @Value("${boothlock.upload.event-dir:data/uploads/event}") String eventUploadDir,
                       Environment environment,
                       BoothRepository boothRepository,
                       StaffAccountRepository staffAccountRepository,
                       EventMapRepository eventMapRepository,
                       PlatformTransactionManager transactionManager) {
        this.seedFile = seedFile;
        this.eventUploadDir = Path.of(eventUploadDir).toAbsolutePath().normalize();
        this.environment = environment;
        this.boothRepository = boothRepository;
        this.staffAccountRepository = staffAccountRepository;
        this.eventMapRepository = eventMapRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        Result result = seed();
        log.info("행사 시딩 완료 — 부스 생성 {}·기존 {}, ADMIN 계정 생성 {}·기존 {}, 약도 {}",
                result.boothsCreated(), result.boothsExisting(),
                result.adminsCreated(), result.adminsExisting(),
                result.mapCreated() ? "추가" : "기존과 같음");
    }

    Result seed() {
        try {
            EventSeedPlan plan = new EventSeedParser(environment::getProperty).parse(readSeedFile());
            checkMapImageExists(plan.eventMap());
            return Objects.requireNonNull(transactionTemplate.execute(status -> apply(plan)));
        } catch (EventSeedException e) {
            throw new EventSeedException("행사 시딩 실패 — 서버를 띄우지 않습니다: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new EventSeedException("행사 시딩 실패(DB 반영 중, 전부 롤백됨) — 서버를 띄우지 않습니다: "
                    + e.getClass().getSimpleName(), e);
        }
    }

    private byte[] readSeedFile() {
        if (seedFile == null || seedFile.isBlank())
            throw new EventSeedException("boothlock.seed.enabled=true인데 boothlock.seed.file(시딩 JSON 경로)이 비어 있습니다.");
        Path path = Path.of(seedFile).toAbsolutePath();
        if (!Files.isRegularFile(path)) throw new EventSeedException("시딩 파일이 없습니다: " + path);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new EventSeedException("시딩 파일을 읽을 수 없습니다: " + path, e);
        }
    }

    /**
     * 약도 폴더·파일이 없어도 서버는 뜨고 E2는 200으로 imageUrl을 주지만, 이미지 요청은 404라 손님 지도가 깨진다(실측).
     * 이미 같은 약도가 DB에 있어 새로 넣지 않는 재기동에서도 확인한다 — 파일이 사라진 것도 같은 사고다.
     */
    private void checkMapImageExists(EventSeedPlan.MapSeed map) {
        Path image = eventUploadDir.resolve(map.imageUrl().substring(EventSeedParser.MAP_URL_PREFIX.length())).normalize();
        // 파서가 ..을 막지만 경로 계산 결과로 한 번 더 확인한다
        if (!image.startsWith(eventUploadDir))
            throw new EventSeedException("eventMap.imageUrl이 약도 폴더 밖을 가리킵니다: " + map.imageUrl());
        if (!Files.isRegularFile(image))
            throw new EventSeedException("약도 이미지 파일이 없습니다: " + image + " (imageUrl=" + map.imageUrl()
                    + ", boothlock.upload.event-dir=" + eventUploadDir + ")");
    }

    private Result apply(EventSeedPlan plan) {
        // DATETIME은 소수 초를 반올림할 수 있다 — JWT pwdAt(초 단위)과 어긋날 여지를 없앤다
        LocalDateTime now = LocalDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS);
        int boothsCreated = 0, boothsExisting = 0, adminsCreated = 0, adminsExisting = 0;

        for (EventSeedPlan.BoothSeed seed : plan.booths()) {
            List<BoothEntity> sameName = boothRepository.findByName(seed.name());
            if (sameName.size() > 1)
                throw new EventSeedException("이름이 \"" + seed.name() + "\"인 부스가 DB에 " + sameName.size()
                        + "건 있어 어느 부스에 계정을 붙일지 정할 수 없습니다.");
            BoothEntity booth;
            if (sameName.isEmpty()) {
                booth = new BoothEntity(seed.name(), seed.bankAccount(), seed.operatingHours());
                booth.updateCategory(seed.category());
                booth.updateMapPosition(seed.mapX(), seed.mapY());
                booth = boothRepository.save(booth);
                boothsCreated++;
            } else {
                booth = sameName.getFirst();
                boothsExisting++;
            }

            EventSeedPlan.AdminSeed admin = seed.admin();
            Optional<StaffAccountEntity> existing = staffAccountRepository.findByLoginId(admin.loginId());
            if (existing.isPresent()) {
                StaffAccountEntity account = existing.get();
                boolean sameBooth = account.getBooth() != null && booth.getId().equals(account.getBooth().getId());
                if (!sameBooth || account.getRole() != StaffRole.ADMIN)
                    throw new EventSeedException("loginId \"" + admin.loginId() + "\"는 이미 다른 부스 또는 다른 역할의 계정입니다 ("
                            + "부스 \"" + seed.name() + "\"의 ADMIN으로 쓸 수 없음).");
                adminsExisting++;
            } else {
                staffAccountRepository.save(new StaffAccountEntity(
                        booth, admin.loginId(), passwordEncoder.encode(admin.password()), now, StaffRole.ADMIN));
                adminsCreated++;
            }
        }

        EventSeedPlan.MapSeed map = plan.eventMap();
        boolean sameAsLatest = eventMapRepository.findFirstByOrderByIdDesc()
                .filter(latest -> latest.getImageUrl().equals(map.imageUrl())
                        && latest.getWidth() == map.width() && latest.getHeight() == map.height())
                .isPresent();
        if (!sameAsLatest) eventMapRepository.save(new EventMapEntity(map.imageUrl(), map.width(), map.height(), now));

        // 유니크 제약 위반을 커밋 시점이 아니라 여기서 터뜨려 위 메시지 경로로 보낸다
        staffAccountRepository.flush();
        return new Result(boothsCreated, boothsExisting, adminsCreated, adminsExisting, !sameAsLatest);
    }

    record Result(int boothsCreated, int boothsExisting, int adminsCreated, int adminsExisting, boolean mapCreated) {
    }
}
