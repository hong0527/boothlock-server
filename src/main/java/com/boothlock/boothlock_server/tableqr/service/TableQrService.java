package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.dto.QrFile;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.support.QrImageComposer;
import com.boothlock.boothlock_server.tableqr.support.TableLabelComparator;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfWriter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * O4 QR 단건 다운로드·O4b QR 전체 일괄 PDF (명세서 O4·O4b) — JWT·STAFF 인증은 황대겸의 BoothJwtProvider·BoothInfoService를 재사용한다.
 * QR은 {customer.base-url}/t/{tableToken}을 인코딩하고, 같은 도메인 문구를 이미지에 병기해 위조 QR을 가려낸다.
 */
@Service
public class TableQrService {

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final TableRepository tableRepository;
    private final String customerBaseUrl;

    public TableQrService(BoothJwtProvider jwtProvider,
                           BoothInfoService boothInfoService,
                           TableRepository tableRepository,
                           @Value("${boothlock.customer.base-url}") String customerBaseUrl) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.tableRepository = tableRepository;
        this.customerBaseUrl = customerBaseUrl;
    }

    /** O4 QR 단건 다운로드 — format=png(기본)|pdf. 타 부스 테이블은 404로 존재를 숨긴다 */
    @Transactional(readOnly = true)
    public QrFile downloadSingle(String authorization, Long tableId, String format) {
        BoothEntity staffBooth = authenticatedBooth(authorization);
        String resolvedFormat = resolveFormat(format);

        TableEntity table = tableRepository.findById(tableId)
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
        if (!table.getBooth().getId().equals(staffBooth.getId())) {
            throw new NotFoundException("테이블을 찾을 수 없습니다.");
        }

        BufferedImage qrImage = composeFor(table);
        String filenameBase = "table-" + table.getLabel() + "-qr";
        return "pdf".equals(resolvedFormat)
                ? new QrFile(toSinglePagePdf(qrImage), "application/pdf", filenameBase + ".pdf")
                : new QrFile(toPng(qrImage), "image/png", filenameBase + ".png");
    }

    /** O4b QR 전체 일괄 PDF — 행사 준비용, 라벨 순으로 카드 1장당 1페이지 */
    @Transactional(readOnly = true)
    public QrFile downloadAll(String authorization) {
        BoothEntity staffBooth = authenticatedBooth(authorization);

        List<TableEntity> tables = tableRepository.findByBoothId(staffBooth.getId()).stream()
                .sorted(TableLabelComparator.BY_LABEL)
                .toList();
        if (tables.isEmpty()) {
            throw new NotFoundException("등록된 테이블이 없습니다.");
        }

        List<BufferedImage> images = tables.stream().map(this::composeFor).toList();
        return new QrFile(toMultiPagePdf(images), "application/pdf", "booth-tables-qr.pdf");
    }

    private BufferedImage composeFor(TableEntity table) {
        String qrContent = customerBaseUrl + "/t/" + table.getTableToken();
        return QrImageComposer.compose(qrContent, table.getLabel(), customerBaseUrl);
    }

    private String resolveFormat(String format) {
        if (format == null || format.isBlank()) {
            return "png";
        }
        String normalized = format.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"png".equals(normalized) && !"pdf".equals(normalized)) {
            throw new InvalidRequestException("format은 png 또는 pdf만 지원합니다.");
        }
        return normalized;
    }

    private BoothEntity authenticatedBooth(String authorization) {
        Jwt jwt = jwtProvider.verify(authorization);
        StaffAccountEntity staff = boothInfoService.authenticate(jwt);
        BoothEntity staffBooth = staff.getBooth();
        if (staffBooth == null) {
            throw new ForbiddenException();
        }
        return staffBooth;
    }

    private byte[] toPng(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("QR PNG 생성에 실패했습니다.", e);
        }
    }

    private byte[] toSinglePagePdf(BufferedImage image) {
        return toPdf(List.of(image));
    }

    private byte[] toMultiPagePdf(List<BufferedImage> images) {
        return toPdf(images);
    }

    private byte[] toPdf(List<BufferedImage> images) {
        BufferedImage first = images.get(0);
        Document document = new Document(new Rectangle(first.getWidth(), first.getHeight()), 0, 0, 0, 0);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfWriter.getInstance(document, baos);
            document.open();
            addPage(document, first);
            for (int i = 1; i < images.size(); i++) {
                BufferedImage image = images.get(i);
                document.setPageSize(new Rectangle(image.getWidth(), image.getHeight()));
                document.newPage();
                addPage(document, image);
            }
            document.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("QR PDF 생성에 실패했습니다.", e);
        }
    }

    private void addPage(Document document, BufferedImage image) throws DocumentException, IOException {
        Image pdfImage = Image.getInstance(toPng(image));
        pdfImage.setAbsolutePosition(0, 0);
        document.add(pdfImage);
    }
}
