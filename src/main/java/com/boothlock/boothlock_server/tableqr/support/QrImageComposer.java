package com.boothlock.boothlock_server.tableqr.support;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * O4·O4b QR 이미지 조립 — QR 코드 아래에 테이블 라벨과 공식 도메인 문구를 병기한다(위조 QR 방지, 명세서 O4).
 * 라벨·도메인 모두 영숫자·URL(ASCII)만 다루므로 별도 한글 폰트 임베드 없이 기본 논리 폰트로 렌더링한다.
 */
public final class QrImageComposer {

    private static final int QR_SIZE = 360;
    private static final int PADDING = 24;
    private static final int TEXT_BLOCK_HEIGHT = 64;
    private static final int CANVAS_SIZE = QR_SIZE + PADDING * 2;
    private static final int CANVAS_HEIGHT = CANVAS_SIZE + TEXT_BLOCK_HEIGHT;

    private QrImageComposer() {
    }

    /** @param qrContent QR에 인코딩할 URL, label 테이블 라벨(위 줄), domainText 공식 도메인 문구(아래 줄) */
    public static BufferedImage compose(String qrContent, String label, String domainText) {
        BufferedImage qrImage = encode(qrContent);

        BufferedImage canvas = new BufferedImage(CANVAS_SIZE, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, CANVAS_SIZE, CANVAS_HEIGHT);
            g.drawImage(qrImage, PADDING, PADDING, null);

            g.setColor(Color.BLACK);
            drawCentered(g, label, new Font(Font.SANS_SERIF, Font.BOLD, 22), CANVAS_SIZE / 2, CANVAS_SIZE + 26);
            drawCentered(g, domainText, new Font(Font.SANS_SERIF, Font.PLAIN, 14), CANVAS_SIZE / 2, CANVAS_SIZE + 50);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static BufferedImage encode(String content) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 0);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
            return MatrixToImageWriter.toBufferedImage(matrix);
        } catch (WriterException e) {
            throw new IllegalStateException("QR 코드 생성에 실패했습니다.", e);
        }
    }

    private static void drawCentered(Graphics2D g, String text, Font font, int centerX, int baselineY) {
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        int x = centerX - metrics.stringWidth(text) / 2;
        g.drawString(text, x, baselineY);
    }
}
