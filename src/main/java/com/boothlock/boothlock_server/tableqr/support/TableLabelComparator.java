package com.boothlock.boothlock_server.tableqr.support;

import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 테이블 라벨 자연 정렬 — "A-2" &lt; "A-10"처럼 숫자 구간을 값으로 비교한다(O3/O4b 공용). */
public final class TableLabelComparator {

    private static final Pattern CHUNK_PATTERN = Pattern.compile("\\d+|\\D+");

    public static final Comparator<TableEntity> BY_LABEL = Comparator.comparing(
            TableEntity::getLabel, TableLabelComparator::compareLabels);

    private TableLabelComparator() {
    }

    private static int compareLabels(String left, String right) {
        Matcher leftMatcher = CHUNK_PATTERN.matcher(left);
        Matcher rightMatcher = CHUNK_PATTERN.matcher(right);
        while (leftMatcher.find() && rightMatcher.find()) {
            String leftChunk = leftMatcher.group();
            String rightChunk = rightMatcher.group();
            int result;
            if (Character.isDigit(leftChunk.charAt(0)) && Character.isDigit(rightChunk.charAt(0))) {
                result = Long.compare(Long.parseLong(leftChunk), Long.parseLong(rightChunk));
                if (result == 0) {
                    result = Integer.compare(leftChunk.length(), rightChunk.length());
                }
            } else {
                result = leftChunk.compareTo(rightChunk);
            }
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.length(), right.length());
    }
}
